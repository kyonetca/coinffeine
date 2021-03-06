package coinffeine.peer.api

import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.CurrencyAmount
import coinffeine.model.payment.PaymentProcessor

/** Represents how the app interact with a payment processor */
trait CoinffeinePaymentProcessor {

  def accountId: PaymentProcessor.AccountId

  /** Get the current balance if possible */
  def currentBalance(): Option[CurrencyAmount[Euro.type]]
}
