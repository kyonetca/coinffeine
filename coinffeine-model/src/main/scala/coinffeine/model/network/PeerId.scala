package coinffeine.model.network

/** Identifies a peer on the Coinffeine network */
case class PeerId(value: String) {
  override def toString = s"peer $value"
}
