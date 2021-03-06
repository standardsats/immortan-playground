/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq

import java.security.SecureRandom

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin._
import scodec.Attempt
import scodec.bits.{BitVector, ByteVector}

import scala.util.{Failure, Success, Try}


package object eclair {

  /**
   * We are using 'new SecureRandom()' instead of 'SecureRandom.getInstanceStrong()' because the latter can hang on Linux
   * See http://bugs.java.com/view_bug.do?bug_id=6521844 and https://tersesystems.com/2015/12/17/the-right-way-to-use-securerandom/
   */
  val secureRandom = new SecureRandom()

  def randomBytes(length: Int): ByteVector = {
    val buffer = new Array[Byte](length)
    secureRandom.nextBytes(buffer)
    ByteVector.view(buffer)
  }

  def randomBytes32: ByteVector32 = ByteVector32(randomBytes(32))

  def randomBytes64: ByteVector64 = ByteVector64(randomBytes(64))

  def randomKey: PrivateKey = PrivateKey(randomBytes32)

  lazy val invalidPubKey: PublicKey = PublicKey.fromBin(ByteVector.fromValidHex("02" * 33), checkValid = false)

  def toLongId(fundingTxHash: ByteVector32, fundingOutputIndex: Int): ByteVector32 = {
    require(fundingOutputIndex < 65536, "fundingOutputIndex must not be greater than FFFF")
    require(fundingTxHash.size == 32, "fundingTxHash must be of length 32B")
    val channelId = ByteVector32(fundingTxHash.take(30) :+ (fundingTxHash(30) ^ (fundingOutputIndex >> 8)).toByte :+ (fundingTxHash(31) ^ fundingOutputIndex).toByte)
    channelId
  }

  def serializationResult(attempt: Attempt[BitVector]): ByteVector = attempt match {
    case Attempt.Successful(bin) => bin.toByteVector
    case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
  }

  /**
   * Converts fee rate in satoshi-per-bytes to fee rate in satoshi-per-kw
   *
   * @param feeratePerByte fee rate in satoshi-per-bytes
   * @return fee rate in satoshi-per-kw
   */
  def feerateByte2Kw(feeratePerByte: Long): Long = feerateKB2Kw(feeratePerByte * 1000)

  /**
   * Converts fee rate in satoshi-per-kw to fee rate in satoshi-per-byte
   *
   * @param feeratePerKw fee rate in satoshi-per-kw
   * @return fee rate in satoshi-per-byte
   */
  def feerateKw2Byte(feeratePerKw: Long): Long = feeratePerKw / 250

  /**
   * why 253 and not 250 since feerate-per-kw is feerate-per-kb / 250 and the minimum relay fee rate is 1000 satoshi/Kb ?
   *
   * because bitcoin core uses neither the actual tx size in bytes or the tx weight to check fees, but a "virtual size"
   * which is (3 * weight) / 4 ...
   * so we want :
   * fee > 1000 * virtual size
   * feerate-per-kw * weight > 1000 * (3 * weight / 4)
   * feerate_per-kw > 250 + 3000 / (4 * weight)
   * with a conservative minimum weight of 400, we get a minimum feerate_per-kw of 253
   *
   * see https://github.com/ElementsProject/lightning/pull/1251
   **/
  val MinimumFeeratePerKw = 253

  /**
   * minimum relay fee rate, in satoshi per kilo
   * bitcoin core uses virtual size and not the actual size in bytes, see above
   **/
  val MinimumRelayFeeRate = 1000

  /**
   * Converts fee rate in satoshi-per-kilobytes to fee rate in satoshi-per-kw
   *
   * @param feeratePerKB fee rate in satoshi-per-kilobytes
   * @return fee rate in satoshi-per-kw
   */
  def feerateKB2Kw(feeratePerKB: Long): Long = Math.max(feeratePerKB / 4, MinimumFeeratePerKw)

  /**
   * Converts fee rate in satoshi-per-kw to fee rate in satoshi-per-kilobyte
   *
   * @param feeratePerKw fee rate in satoshi-per-kw
   * @return fee rate in satoshi-per-kilobyte
   */
  def feerateKw2KB(feeratePerKw: Long): Long = feeratePerKw * 4

  def isPay2PubkeyHash(address: String): Boolean = address.startsWith("1") || address.startsWith("m") || address.startsWith("n")

  def proportionalFee(paymentAmount: MilliSatoshi, proportionalFee: Long): MilliSatoshi = (paymentAmount * proportionalFee) / 1000000

  def nodeFee(baseFee: MilliSatoshi, proportionalRatio: Long, paymentAmount: MilliSatoshi): MilliSatoshi = baseFee + proportionalFee(paymentAmount, proportionalRatio)

  // proportional^(exponent = 1) + ln(proportional)^(logExponent = 0) is linear
  // proportional^(exponent = 0.97) + ln(proportional)^(logExponent = 3.9) gives moderate discounts
  // proportional^(exponent = 0.90) + ln(proportional)^(logExponent = 4.0) gives large discounts for large amounts
  // proportional^(exponent = 0) + ln(proportional)^(logExponent = 0) gives base + 2 msat, independent of payment amount
  def trampolineFee(proportional: Long, baseFee: MilliSatoshi, exponent: Double, logExponent: Double): MilliSatoshi = {
    val nonLinearFeeMsat = math.pow(proportional, exponent) + math.pow(math.log(proportional), logExponent)
    baseFee + MilliSatoshi(nonLinearFeeMsat.ceil.toLong)
  }

  /**
   * @param address   base58 of bech32 address
   * @param chainHash hash of the chain we're on, which will be checked against the input address
   * @return the public key script that matches the input address.
   */
  def addressToPublicKeyScript(address: String, chainHash: ByteVector32): Seq[ScriptElt] = {
    Try(Base58Check.decode(address)) match {
      case Success((Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)) if chainHash == Block.TestnetGenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash => Script.pay2pkh(pubKeyHash)
      case Success((Base58.Prefix.PubkeyAddress, pubKeyHash)) if chainHash == Block.LivenetGenesisBlock.hash => Script.pay2pkh(pubKeyHash)
      case Success((Base58.Prefix.ScriptAddressTestnet, scriptHash)) if chainHash == Block.TestnetGenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash => OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil
      case Success((Base58.Prefix.ScriptAddress, scriptHash)) if chainHash == Block.LivenetGenesisBlock.hash => OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil
      case Success(_) => throw new IllegalArgumentException("base58 address does not match our blockchain")
      case Failure(base58error) =>
        Try(Bech32.decodeWitnessAddress(address)) match {
          case Success((_, version, _)) if version != 0.toByte => throw new IllegalArgumentException(s"invalid version $version in bech32 address")
          case Success((_, _, bin)) if bin.length != 20 && bin.length != 32 => throw new IllegalArgumentException("hash length in bech32 address must be either 20 or 32 bytes")
          case Success(("bc", _, bin)) if chainHash == Block.LivenetGenesisBlock.hash => OP_0 :: OP_PUSHDATA(bin) :: Nil
          case Success(("tb", _, bin)) if chainHash == Block.TestnetGenesisBlock.hash => OP_0 :: OP_PUSHDATA(bin) :: Nil
          case Success(("bcrt", _, bin)) if chainHash == Block.RegtestGenesisBlock.hash => OP_0 :: OP_PUSHDATA(bin) :: Nil
          case Success(_) => throw new IllegalArgumentException("bech32 address does not match our blockchain")
          case Failure(bech32error) => throw new IllegalArgumentException(s"$address is neither a valid Base58 address ($base58error) nor a valid Bech32 address ($bech32error)")
        }
    }
  }

  implicit class MilliSatoshiLong(private val n: Long) extends AnyVal {
    def msat = MilliSatoshi(n)
  }

  // We implement Numeric to take advantage of operations such as sum, sort or min/max on iterables.
  implicit object NumericMilliSatoshi extends Numeric[MilliSatoshi] {
    // @formatter:off
    override def plus(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = x + y
    override def minus(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = x - y
    override def times(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = MilliSatoshi(x.toLong * y.toLong)
    override def negate(x: MilliSatoshi): MilliSatoshi = -x
    override def fromInt(x: Int): MilliSatoshi = MilliSatoshi(x)
    override def toInt(x: MilliSatoshi): Int = x.toLong.toInt
    override def toLong(x: MilliSatoshi): Long = x.toLong
    override def toFloat(x: MilliSatoshi): Float = x.toLong
    override def toDouble(x: MilliSatoshi): Double = x.toLong
    override def compare(x: MilliSatoshi, y: MilliSatoshi): Int = x.compare(y)
    // @formatter:on
  }

  implicit class ToMilliSatoshiConversion(amount: BtcAmount) {
    // @formatter:off
    def toMilliSatoshi: MilliSatoshi = MilliSatoshi.toMilliSatoshi(amount)
    def +(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi + other
    def -(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi - other
    // @formatter:on
  }
}