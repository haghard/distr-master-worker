package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.delivery.internal.{DeliverySerializable, ProducerControllerImpl}
import akka.actor.typed.delivery.{ConsumerController, DurableProducerQueue, ProducerController}
import akka.cluster.ddata.protobuf.SerializationSupport
import akka.protobufv3.internal.CodedOutputStream
import akka.serialization.{ByteBufferSerializer, SerializationExtension, SerializerWithStringManifest}
import com.dsim.domain.v1.WorkerTaskPB
import com.dsim.rdelivery.serialization.{ByteBufferInputStream, ByteBufferOutputStream}

import java.nio.ByteBuffer
import scala.util.Using
import scala.util.Using.Releasable
import ReliableDeliveryDirectSerializer2.*
import akka.cluster.ddata.PayloadSizeAggregator
import akka.remote.RARP

object ReliableDeliveryDirectSerializer2 {

  implicit val releasable: Releasable[CodedOutputStream] = (r: CodedOutputStream) => r.flush()
}

/** https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
  *
  * Artery introduced a new serialization mechanism. This implementation takes advantage of new Artery serialization
  * mechanism which allows the ByteBufferSerializer to directly write into and read from a shared java.nio.ByteBuffer
  * instead of being forced to allocate and return an Array[Byte] for each serialized message.
  */

/*

actor {
  serialization-identifiers {
    "akka.cluster.typed.internal.delivery.ReliableDeliverySerializer" = 36
  }
  serializers {
    reliable-delivery = "akka.cluster.typed.internal.delivery.ReliableDeliverySerializer"
  }
  serialization-bindings {
    "akka.actor.typed.delivery.internal.DeliverySerializable" = reliable-delivery
  }
}
 */
class ReliableDeliveryDirectSerializer2(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with SerializationSupport
    with ByteBufferSerializer
    with ProtocSupport {

  private val SequencedMessageManifest = "a"
  private val AckManifest              = "b"
  private val RequestManifest          = "c"

  private val ResendManifest           = "d"
  private val RegisterConsumerManifest = "e"

  private val DurableQueueMessageSentManifest = "f" //
  private val DurableQueueConfirmedManifest   = "g" //
  private val DurableQueueStateManifest       = "h"
  private val DurableQueueCleanupManifest     = "i"

  val internal = new akka.cluster.typed.internal.delivery.ReliableDeliverySerializer(system)
  // you need to know the maximum size in bytes of the serialized messages
  val maxFrameSize = system.settings.config.getBytes("akka.remote.artery.advanced.maximum-frame-size").toInt
  val bufferPool   = new akka.io.DirectByteBufferPool(defaultBufferSize = maxFrameSize, maxPoolEntries = 6)

  override val identifier: Int = internal.identifier

  override def manifest(o: AnyRef): String =
    internal.manifest(o)

  val payloadSizeAggregator = {
    val sizeExceeding  = system.settings.config.getBytes("akka.remote.artery.log-frame-size-exceeding").toInt
    val remoteProvider = RARP(system).provider
    val remoteSettings = remoteProvider.remoteSettings
    val maxFrameSize   = remoteSettings.Artery.Advanced.MaximumFrameSize
    new PayloadSizeAggregator(system.log, sizeExceeding, maxFrameSize)
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val buf = bufferPool.acquire()
    try {
      toBinary(o, buf)
      buf.flip()
      val bytes = new Array[Byte](buf.remaining)
      buf.get(bytes)
      bytes
    } finally
      bufferPool.release(buf)
  }

  // buffer based avoiding a copy for artery
  override def toBinary(obj: AnyRef, directByteBuffer: ByteBuffer): Unit =
    obj match {
      case consCmd: akka.actor.typed.delivery.ConsumerController.Command[_] =>
        consCmd match {
          case m: ConsumerController.SequencedMessage[_] =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(
              sequencedMessageToBinary(payloadSizeAggregator, m).writeTo(_)
            )
          case _ =>
            system.log.warning(s"toBinary ${obj.getClass.getName} ${directByteBuffer.isDirect}")
            val bytes = internal.toBinary(obj)
            Using.resource(new ByteBufferOutputStream(directByteBuffer))(_.write(bytes))
        }

      //
      case prodEv: akka.actor.typed.delivery.DurableProducerQueue.Event =>
        prodEv match {
          case m: DurableProducerQueue.MessageSent[_] =>
            system.log.warning("MessageSent: [{}:{}] {}", m.seqNr, m.confirmationQualifier, directByteBuffer.isDirect)
            // Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueMessageSentToProto(m).writeTo(_))
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(
              durableQueueMessageSentToProto(payloadSizeAggregator, m).writeTo(_)
            )
          case m: DurableProducerQueue.Confirmed =>
            system.log.warning("Confirmed:[{}:{}] {}", m.seqNr, m.confirmationQualifier, directByteBuffer.isDirect)
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(
              durableQueueConfirmedToProto(m.confirmationQualifier, m.seqNr, m.timestampMillis).writeTo(_)
            )
          case m: DurableProducerQueue.Cleanup =>
            Using.resource(CodedOutputStream.newInstance(directByteBuffer))(
              durableQueueCleanupToBinary(m).writeTo(_)
            )
        }

      case m: ProducerControllerImpl.Ack =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(ackToBinary(m).writeTo(_))

      case m: ProducerControllerImpl.Request =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(requestToBinary(m).writeTo(_))

      case m: ProducerControllerImpl.Resend =>
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(resendToBinary(m).writeTo(_))

      case m: ProducerController.RegisterConsumer[_] =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(registerConsumerToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(registerConsumerToBinary(m).writeTo(_))

      case m: DurableProducerQueue.State[WorkerTaskPB] =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueStateToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(
          durableQueueStateToBinary(m, payloadSizeAggregator).writeTo(_)
        )

      case _ =>
        system.log.error(s"internal.toBinary(${obj.getClass.getName})")
        val bytes = internal.toBinary(obj)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(_.write(bytes))
    }

  override def fromBinary(directByteBuffer: ByteBuffer, manifest: String): AnyRef =
    manifest match {
      case SequencedMessageManifest =>
        val m: ConsumerController.SequencedMessage[WorkerTaskPB] = sequencedMessageFromBinary(directByteBuffer)
        m
      case DurableQueueStateManifest =>
        val s: DurableProducerQueue.State[WorkerTaskPB] = durableQueueStateFromBinary(directByteBuffer)
        system.log.warning(
          s"fromBinary: DurableProducerQueue.State [curSeqNr=${s.currentSeqNr},highestConfirmedSeqNr=${s.highestConfirmedSeqNr}] Unconfirmed:[${s.unconfirmed.map(_.seqNr).mkString(",")}]"
        )
        s

      case AckManifest =>
        ackFromBinary(directByteBuffer)
      case RequestManifest =>
        requestFromBinary(directByteBuffer)
      case DurableQueueMessageSentManifest =>
        val msg = durableQueueMessageSentFromBinary(directByteBuffer)
        println(s"${msg.seqNr} at ${msg.timestampMillis}")
        msg
      case DurableQueueConfirmedManifest =>
        val confirmed = durableQueueConfirmedFromBinary(directByteBuffer)
        println(s"Confirmed: ${confirmed.seqNr}/${confirmed.confirmationQualifier} at${confirmed.timestampMillis}")
        confirmed
      case ResendManifest =>
        val msg = resendFromBinary(directByteBuffer)
        println(s"Resend: ${msg.fromSeqNr}")
        msg
      case RegisterConsumerManifest =>
        val msg = registerConsumerFromBinary(directByteBuffer)
        msg
      case DurableQueueCleanupManifest =>
        durableQueueCleanupFromBinary(directByteBuffer)
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported msg in fromBinary($manifest) !")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)
}
