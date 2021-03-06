package coinffeine.peer.exchange

import scala.concurrent.duration._

import akka.actor.{ActorRef, Props, Terminated}
import akka.testkit.{TestActor, TestProbe}
import akka.util.Timeout
import org.scalatest.concurrent.Eventually

import coinffeine.model.bitcoin._
import coinffeine.model.bitcoin.test.BitcoinjTest
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.exchange.Both
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.BitcoinPeerActor._
import coinffeine.peer.bitcoin.BlockchainActor._
import coinffeine.peer.exchange.ExchangeActor._
import coinffeine.peer.exchange.ExchangeTransactionBroadcastActor.{UnexpectedTxBroadcast => _, _}
import coinffeine.peer.exchange.handshake.HandshakeActor.{HandshakeFailure, HandshakeSuccess, StartHandshake}
import coinffeine.peer.exchange.micropayment.MicroPaymentChannelActor
import coinffeine.peer.exchange.protocol.MockExchangeProtocol
import coinffeine.peer.exchange.test.CoinffeineClientTest.SellerPerspective
import coinffeine.peer.exchange.test.{CoinffeineClientTest, TestMessageQueue}
import coinffeine.peer.payment.MockPaymentProcessorFactory

class DefaultExchangeActorTest extends CoinffeineClientTest("buyerExchange")
  with SellerPerspective with BitcoinjTest with Eventually {

  implicit def testTimeout = new Timeout(5 second)
  private val protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitRefundSignatureTimeout = 1 second,
    refundSignatureAbortTimeout = 1 minute)

  private val handshakeActorMessageQueue = new TestMessageQueue()
  private val micropaymentChannelActorMessageQueue = new TestMessageQueue()
  private val transactionBroadcastActorMessageQueue = new TestMessageQueue()

  private val deposits = Both(
    buyer = new Hash(List.fill(64)("0").mkString),
    seller = new Hash(List.fill(64)("1").mkString)
  )
  private val dummyTx = ImmutableTransaction(new MutableTransaction(network))
  private val dummyPaymentProcessor = system.actorOf(
    new MockPaymentProcessorFactory(List.empty)
      .newProcessor(fiatAddress = "", initialBalance = Seq.empty)
  )

  trait Fixture {
    val listener = TestProbe()
    val blockchain = TestProbe()
    val peers = TestProbe()
    val wallet = createWallet(user.bitcoinKey, exchange.amounts.sellerDeposit)
    val handshakeProps = TestActor.props(handshakeActorMessageQueue.queue)
    val micropaymentChannelProps = TestActor.props(micropaymentChannelActorMessageQueue.queue)
    val transactionBroadcastActorProps = TestActor.props(transactionBroadcastActorMessageQueue.queue)
    val actor = system.actorOf(Props(new DefaultExchangeActor[Euro.type](
      handshakeProps,
      micropaymentChannelProps,
      transactionBroadcastActorProps,
      new MockExchangeProtocol,
      protocolConstants
    )))
    listener.watch(actor)

    def withActor(actorName: String)(body: ActorRef => Unit) = {
      val actorSelection = system.actorSelection(actor.path / actorName)
      eventually {
        actorSelection.resolveOne().futureValue
      }
      whenReady(actorSelection.resolveOne())(body)
    }

    def startExchange(): Unit = {
      listener.send(actor,
        StartExchange(exchange, userRole, user, wallet, dummyPaymentProcessor, gateway.ref, peers.ref))
      peers.expectMsg(RetrieveBlockchainActor)
      peers.reply(BlockchainActorReference(blockchain.ref))
    }

    def givenHandshakeSuccess(): Unit = {
      withActor(HandshakeActorName) { handshakeActor =>
        handshakeActorMessageQueue.expectMsgClass[StartHandshake[_]]()
        actor.tell(HandshakeSuccess(handshakingExchange, deposits, dummyTx), handshakeActor)
      }
      transactionBroadcastActorMessageQueue.expectMsg(
        StartBroadcastHandling(dummyTx, peers.ref, Set(actor)))
    }

    def givenTransactionsAreFound(): Unit = {
      shouldWatchForTheTransactions()
      givenTransactionIsFound(deposits.buyer)
      givenTransactionIsFound(deposits.seller)
      withActor(MicroPaymentChannelActorName) { micropaymentChannelActor =>
        transactionBroadcastActorMessageQueue.expectMsg(
          SetMicropaymentActor(micropaymentChannelActor))
      }
    }

    def givenTransactionIsFound(txId: Hash): Unit = {
      blockchain.reply(TransactionFound(txId, dummyTx))
    }

    def givenTransactionIsNotFound(txId: Hash): Unit = {
      blockchain.reply(TransactionNotFound(txId))
    }

    def givenTransactionIsCorrectlyBroadcast(): Unit =
      withActor(TransactionBroadcastActorName) { txBroadcaster =>
        transactionBroadcastActorMessageQueue.expectMsg(FinishExchange)
        actor.tell(ExchangeFinished(TransactionPublished(dummyTx, dummyTx)), txBroadcaster)
      }

    def givenMicropaymentChannelSuccess(): Unit =
      withActor(MicroPaymentChannelActorName) { micropaymentChannelActor =>
        micropaymentChannelActorMessageQueue.expectMsg(MicroPaymentChannelActor.StartMicroPaymentChannel(
          runningExchange, protocolConstants, dummyPaymentProcessor, gateway.ref, Set(actor)
        ))
        actor.tell(MicroPaymentChannelActor.ExchangeSuccess(Some(dummyTx)), micropaymentChannelActor)
      }

    def shouldWatchForTheTransactions(): Unit = {
      blockchain.expectMsgAllOf(
        WatchPublicKey(counterpart.bitcoinKey),
        WatchPublicKey(user.bitcoinKey))
      blockchain.expectMsgAllOf(
        RetrieveTransaction(deposits.buyer),
        RetrieveTransaction(deposits.seller)
      )
    }
  }

  "The exchange actor" should "report an exchange success when handshake, exchange and broadcast work" in
    new Fixture {
      startExchange()
      givenHandshakeSuccess()
      givenTransactionsAreFound()
      givenMicropaymentChannelSuccess()
      givenTransactionIsCorrectlyBroadcast()
      listener.expectMsg(ExchangeSuccess(null)) // TODO: figure out how the successful exchange looks like
      listener.expectMsgClass(classOf[Terminated])
      system.stop(actor)
    }

  it should "report a failure if the handshake fails" in new Fixture {
    startExchange()
    val error = new Error("Handshake error")
    withActor(HandshakeActorName) { handshakeActor =>
      handshakeActorMessageQueue.expectMsgClass[StartHandshake[_]]()
      actor.tell(HandshakeFailure(error), handshakeActor)
    }
    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the blockchain can't find the commitment txs" in new Fixture {
    startExchange()
    givenHandshakeSuccess()
    shouldWatchForTheTransactions()
    givenTransactionIsFound(deposits.buyer)
    givenTransactionIsNotFound(deposits.seller)

    listener.expectMsg(ExchangeFailure(new CommitmentTxNotInBlockChain(deposits.seller)))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the actual exchange fails" in new Fixture {
    startExchange()
    givenHandshakeSuccess()
    givenTransactionsAreFound()

    val error = new Error("exchange failure")
    withActor(MicroPaymentChannelActorName) { micropaymentChannelActor =>
      micropaymentChannelActorMessageQueue.expectMsg(MicroPaymentChannelActor.StartMicroPaymentChannel(
        runningExchange, protocolConstants, dummyPaymentProcessor, gateway.ref, Set(actor)
      ))
      actor.tell(MicroPaymentChannelActor.ExchangeFailure(error), micropaymentChannelActor)
    }

    givenTransactionIsCorrectlyBroadcast()

    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the broadcast failed" in new Fixture {
    startExchange()
    givenHandshakeSuccess()
    givenTransactionsAreFound()
    givenMicropaymentChannelSuccess()
    val broadcastError = new Error("failed to broadcast")
    withActor(TransactionBroadcastActorName) { txBroadcaster =>
      transactionBroadcastActorMessageQueue.expectMsg(FinishExchange)
      actor.tell(ExchangeFinishFailure(broadcastError), txBroadcaster)
    }
    listener.expectMsg(ExchangeFailure(TxBroadcastFailed(broadcastError)))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the broadcast succeeds with an unexpected transaction" in new Fixture {
    startExchange()
    givenHandshakeSuccess()
    givenTransactionsAreFound()
    givenMicropaymentChannelSuccess()
    val unexpectedTx = ImmutableTransaction {
      val newTx = dummyTx.get
      newTx.setLockTime(40)
      newTx
    }
    withActor(TransactionBroadcastActorName) { txBroadcaster =>
      transactionBroadcastActorMessageQueue.expectMsg(FinishExchange)
      actor.tell(ExchangeFinished(TransactionPublished(unexpectedTx, unexpectedTx)), txBroadcaster)
    }
    listener.expectMsg(ExchangeFailure(UnexpectedTxBroadcast(unexpectedTx, dummyTx)))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the broadcast is forcefully finished because it took too long" in new Fixture {
    startExchange()
    givenHandshakeSuccess()
    givenTransactionsAreFound()
    val midWayTx = ImmutableTransaction {
      val newTx = dummyTx.get
      newTx.setLockTime(40)
      newTx
    }
    withActor(TransactionBroadcastActorName) { txBroadcaster =>
      transactionBroadcastActorMessageQueue.queue should be ('empty)
      actor.tell(ExchangeFinished(TransactionPublished(midWayTx, midWayTx)), txBroadcaster)
    }
    listener.expectMsg(ExchangeFailure(RiskOfValidRefund(midWayTx)))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }
}
