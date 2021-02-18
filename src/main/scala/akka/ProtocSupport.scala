package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.internal.ChunkedMessage
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.typed.internal.protobuf.ReliableDelivery
import akka.protobufv3.internal.ByteString
import akka.remote.ContainerFormats
import akka.remote.ContainerFormats.Payload
import akka.remote.serialization.WrappedPayloadSupport

import java.nio.ByteBuffer

//copied from akka.cluster.typed.internal.delivery.ReliableDeliverySerializer
trait ProtocSupport {

  def system: ExtendedActorSystem

  protected lazy val payloadSupport = new WrappedPayloadSupport(system)
  protected lazy val resolver       = ActorRefResolver(system.toTyped)

  protected def sequencedMessageToBinary(
    m: ConsumerController.SequencedMessage[_]
  ): ReliableDelivery.SequencedMessage = {
    def chunkedMessageToProto(chunk: ChunkedMessage): Payload.Builder = {
      val payloadBuilder = ContainerFormats.Payload.newBuilder()
      payloadBuilder.setEnclosedMessage(ByteString.copyFrom(chunk.serialized.toArray))
      payloadBuilder.setMessageManifest(ByteString.copyFromUtf8(chunk.manifest))
      payloadBuilder.setSerializerId(chunk.serializerId)
      payloadBuilder
    }

    val b = ReliableDelivery.SequencedMessage.newBuilder()
    b.setProducerId(m.producerId)
    b.setSeqNr(m.seqNr)
    b.setFirst(m.first)
    b.setAck(m.ack)
    b.setProducerControllerRef(resolver.toSerializationFormat(m.producerController))

    m.message match {
      case chunk: ChunkedMessage =>
        b.setMessage(chunkedMessageToProto(chunk))
        b.setFirstChunk(chunk.firstChunk)
        b.setLastChunk(chunk.lastChunk)
      case _ =>
        b.setMessage(payloadSupport.payloadBuilder(m.message))
    }

    b.build()
  }

  protected def sequencedMessageFromBinary(
    directByteBuffer: ByteBuffer
  ): ConsumerController.SequencedMessage[Nothing] = {
    val seqMsg = ReliableDelivery.SequencedMessage.parseFrom(directByteBuffer)
    val wrappedMsg =
      if (seqMsg.hasFirstChunk) {
        val manifest =
          if (seqMsg.getMessage.hasMessageManifest) seqMsg.getMessage.getMessageManifest.toStringUtf8 else ""
        ChunkedMessage(
          akka.util.ByteString(seqMsg.getMessage.getEnclosedMessage.toByteArray),
          seqMsg.getFirstChunk,
          seqMsg.getLastChunk,
          seqMsg.getMessage.getSerializerId,
          manifest
        )
      } else {
        payloadSupport.deserializePayload(seqMsg.getMessage)
      }
    ConsumerController.SequencedMessage(
      seqMsg.getProducerId,
      seqMsg.getSeqNr,
      wrappedMsg,
      seqMsg.getFirst,
      seqMsg.getAck
    )(resolver.resolveActorRef(seqMsg.getProducerControllerRef))
  }
}
