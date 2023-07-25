package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.delivery.{ConsumerController, DurableProducerQueue, ProducerController}
import akka.actor.typed.delivery.internal.{ChunkedMessage, ProducerControllerImpl}
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.typed.internal.protobuf.ReliableDelivery
import akka.cluster.typed.internal.protobuf.ReliableDelivery.Confirmed
import akka.protobufv3.internal.{ByteString, CodedOutputStream}
import akka.remote.ContainerFormats
import akka.remote.ContainerFormats.Payload
import akka.remote.serialization.WrappedPayloadSupport

import java.nio.ByteBuffer
import scala.jdk.CollectionConverters.{CollectionHasAsScala, IterableHasAsJava}

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
      case chunk: ChunkedMessage ⇒
        b.setMessage(chunkedMessageToProto(chunk))
        b.setFirstChunk(chunk.firstChunk)
        b.setLastChunk(chunk.lastChunk)
      case _ ⇒
        b.setMessage(payloadSupport.payloadBuilder(m.message))
    }

    b.build()
  }

  protected def ackToBinary(m: ProducerControllerImpl.Ack): ReliableDelivery.Ack = {
    val b = ReliableDelivery.Ack.newBuilder()
    b.setConfirmedSeqNr(m.confirmedSeqNr)
    b.build()
  }

  protected def ackFromBinary(buf: ByteBuffer): AnyRef = {
    val ack = ReliableDelivery.Ack.parseFrom(buf)
    ProducerControllerImpl.Ack(ack.getConfirmedSeqNr)
  }

  protected def requestToBinary(m: ProducerControllerImpl.Request): ReliableDelivery.Request = {
    val b = ReliableDelivery.Request.newBuilder()
    b.setConfirmedSeqNr(m.confirmedSeqNr)
    b.setRequestUpToSeqNr(m.requestUpToSeqNr)
    b.setSupportResend(m.supportResend)
    b.setViaTimeout(m.viaTimeout)
    b.build()
  }

  protected def requestFromBinary(buf: ByteBuffer): AnyRef = {
    val req = ReliableDelivery.Request.parseFrom(buf)
    ProducerControllerImpl.Request(
      req.getConfirmedSeqNr,
      req.getRequestUpToSeqNr,
      req.getSupportResend,
      req.getViaTimeout
    )
  }

  protected def resendToBinary(m: ProducerControllerImpl.Resend) = {
    val b = ReliableDelivery.Resend.newBuilder()
    b.setFromSeqNr(m.fromSeqNr)
    b.build()
  }

  protected def registerConsumerToBinary(m: ProducerController.RegisterConsumer[_]) = {
    val b = ReliableDelivery.RegisterConsumer.newBuilder()
    b.setConsumerControllerRef(resolver.toSerializationFormat(m.consumerController))
    b.build()
  }

  protected def sequencedMessageFromBinary[T](
    byteBuffer: ByteBuffer
  ): ConsumerController.SequencedMessage[T] = {
    val seqMsg = ReliableDelivery.SequencedMessage.parseFrom(byteBuffer)
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
    ConsumerController.SequencedMessage[T](
      seqMsg.getProducerId,
      seqMsg.getSeqNr,
      wrappedMsg,
      seqMsg.getFirst,
      seqMsg.getAck
    )(resolver.resolveActorRef(seqMsg.getProducerControllerRef))
  }

  protected def durableQueueMessageSentToProto(
    m: DurableProducerQueue.MessageSent[_]
  ): ReliableDelivery.MessageSent = {

    def chunkedMessageToProto(chunk: ChunkedMessage): Payload.Builder = {
      val payloadBuilder = ContainerFormats.Payload.newBuilder()
      payloadBuilder.setEnclosedMessage(ByteString.copyFrom(chunk.serialized.toArray))
      payloadBuilder.setMessageManifest(ByteString.copyFromUtf8(chunk.manifest))
      payloadBuilder.setSerializerId(chunk.serializerId)
      payloadBuilder
    }

    val b = ReliableDelivery.MessageSent.newBuilder()
    b.setSeqNr(m.seqNr)
    b.setQualifier(m.confirmationQualifier)
    b.setAck(m.ack)
    b.setTimestamp(m.timestampMillis)

    m.message match {
      case chunk: ChunkedMessage ⇒
        b.setMessage(chunkedMessageToProto(chunk))
        b.setFirstChunk(chunk.firstChunk)
        b.setLastChunk(chunk.lastChunk)
      case _ ⇒
        b.setMessage(payloadSupport.payloadBuilder(m.message))
    }

    b.build()
  }

  protected def durableQueueConfirmedToProto(
    qualifier: String,
    seqNr: DurableProducerQueue.SeqNr,
    timestampMillis: DurableProducerQueue.TimestampMillis
  ): Confirmed = {
    val b = ReliableDelivery.Confirmed.newBuilder()
    b.setSeqNr(seqNr)
    b.setQualifier(qualifier)
    b.setTimestamp(timestampMillis)
    b.build()
  }

  protected def durableQueueStateToBinary(m: DurableProducerQueue.State[_]): ReliableDelivery.State = {
    val b = ReliableDelivery.State.newBuilder()
    b.setCurrentSeqNr(m.currentSeqNr)
    b.setHighestConfirmedSeqNr(m.highestConfirmedSeqNr)
    b.addAllConfirmed(m.confirmedSeqNr.map { case (qualifier, (seqNr, timestamp)) ⇒
      durableQueueConfirmedToProto(qualifier, seqNr, timestamp)
    }.asJava)
    b.addAllUnconfirmed(m.unconfirmed.map(durableQueueMessageSentToProto).asJava)
    b.build()
  }

  protected def durableQueueCleanupToBinary(m: DurableProducerQueue.Cleanup): ReliableDelivery.Cleanup = {
    val b = ReliableDelivery.Cleanup.newBuilder()
    b.addAllQualifiers(m.confirmationQualifiers.asJava)
    b.build()
  }

  protected def durableQueueStateFromBinary[T](buf: ByteBuffer): DurableProducerQueue.State[T] = {

    val state = ReliableDelivery.State.parseFrom(buf)
    DurableProducerQueue.State[T](
      state.getCurrentSeqNr,
      state.getHighestConfirmedSeqNr,
      state.getConfirmedList.asScala
        .map(confirmed ⇒ confirmed.getQualifier -> (confirmed.getSeqNr -> confirmed.getTimestamp))
        .toMap,
      state.getUnconfirmedList.asScala.toVector.map(durableQueueMessageSentFromProto)
    )
  }

  protected def durableQueueMessageSentFromBinary[T](bytes: ByteBuffer): AnyRef = {
    val sent = ReliableDelivery.MessageSent.parseFrom(bytes)
    durableQueueMessageSentFromProto[T](sent)
  }

  private def durableQueueMessageSentFromProto[T](
    sent: ReliableDelivery.MessageSent
  ): DurableProducerQueue.MessageSent[T] = {
    val wrappedMsg =
      if (sent.hasFirstChunk) {
        val manifest =
          if (sent.getMessage.hasMessageManifest) sent.getMessage.getMessageManifest.toStringUtf8 else ""
        ChunkedMessage(
          akka.util.ByteString(sent.getMessage.getEnclosedMessage.toByteArray),
          sent.getFirstChunk,
          sent.getLastChunk,
          sent.getMessage.getSerializerId,
          manifest
        )
      } else {
        payloadSupport.deserializePayload(sent.getMessage)
      }
    DurableProducerQueue
      .MessageSent[T](sent.getSeqNr, wrappedMsg.asInstanceOf[T], sent.getAck, sent.getQualifier, sent.getTimestamp)
  }

  protected def durableQueueConfirmedFromBinary(bytes: ByteBuffer): AnyRef = {
    val confirmed = ReliableDelivery.Confirmed.parseFrom(bytes)
    DurableProducerQueue.Confirmed(confirmed.getSeqNr, confirmed.getQualifier, confirmed.getTimestamp)
  }

  protected def resendFromBinary(bytes: ByteBuffer): AnyRef = {
    val resend = ReliableDelivery.Resend.parseFrom(bytes)
    ProducerControllerImpl.Resend(resend.getFromSeqNr)
  }

  protected def registerConsumerFromBinary(bytes: ByteBuffer): AnyRef = {
    val reg = ReliableDelivery.RegisterConsumer.parseFrom(bytes)
    ProducerController.RegisterConsumer(
      resolver.resolveActorRef[ConsumerController.Command[Any]](reg.getConsumerControllerRef)
    )
  }
}
