package coinffeine.peer.payment

import scala.concurrent.duration._

import akka.actor.{ActorRef, Props}
import akka.util.Timeout

import coinffeine.model.currency.{CurrencyAmount, FiatCurrency}
import coinffeine.model.payment.Payment
import coinffeine.model.payment.PaymentProcessor._

object PaymentProcessorActor {

  /** Initialize the payment processor actor */
  case class Initialize(eventChannel: ActorRef)

  /** A message sent to the payment processor in order to identify itself. */
  case object Identify

  /** A message sent by the payment processor identifying itself. */
  case class Identified(id: Id)

  /** A message sent to the payment processor ordering a new pay.
    *
    * @param to The ID of the receiver account
    * @param amount The amount of fiat currency to pay
    * @param comment The comment to be attached to the payment
    * @tparam C The fiat currency of the payment amount
    */
  case class Pay[C <: FiatCurrency](to: AccountId,
                                    amount: CurrencyAmount[C],
                                    comment: String)

  /** A message sent by the payment processor in order to notify of a successful payment. */
  case class Paid[C <: FiatCurrency](payment: Payment[C])

  /** A message sent by the payment processor to notify a payment failure.
    *
    * @param request The original pay message that cannot be processed.
    * @param error The error that prevented the request to be processed
    * @tparam C The fiat currency of the payment amount
    */
  case class PaymentFailed[C <: FiatCurrency](request: Pay[C], error: Throwable)

  /** A message sent to the payment processor in order to find a payment. */
  case class FindPayment(payment: PaymentId)

  /** A message sent by the payment processor to notify a found payment. */
  case class PaymentFound(payment: Payment[FiatCurrency])

  /** A message sent by the payment processor to notify a not found payment. */
  case class PaymentNotFound(payment: PaymentId)

  /** A message sent by the payment processor to notify an error while finding a payment. */
  case class FindPaymentFailed(payment: PaymentId, error: Throwable)

  /** A message sent to the payment processor to retrieve the current balance
    * in the given currency.
    * */
  case class RetrieveBalance[C <: FiatCurrency](currency: C)

  sealed trait RetrieveBalanceResponse

  /** A message sent by the payment processor reporting the current balance in the
    * given currency.
    * */
  case class BalanceRetrieved[C <: FiatCurrency](balance: CurrencyAmount[C])
    extends RetrieveBalanceResponse

  /** A message sent by the payment processor reporting that the current balance in the
    * given currency cannot be retrieved.
    */
  case class BalanceRetrievalFailed[C <: FiatCurrency](currency: C, error: Throwable)
    extends RetrieveBalanceResponse

  /** Payment processor requests should be considered to have failed after this period */
  val RequestTimeout = Timeout(5.seconds)
}
