package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.delivery.{ConsumerController, DurableProducerQueue, ProducerController}
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.cluster.ddata.PayloadSizeAggregator
import akka.cluster.typed.internal.protobuf.ReliableDelivery
import akka.cluster.typed.internal.protobuf.ReliableDelivery.Confirmed
import akka.remote.ContainerFormats
import akka.serialization.SerializationExtension
import com.dsim.domain.v1.WorkerTaskPB

import java.nio.ByteBuffer
import scala.jdk.CollectionConverters.{CollectionHasAsScala, IterableHasAsJava, IteratorHasAsScala}

trait ProtocSupport {

  def system: ExtendedActorSystem

  protected lazy val serId    = SerializationExtension(system).serializerFor(classOf[WorkerTaskPB]).identifier
  protected lazy val resolver = ActorRefResolver(system.toTyped)

  def sequencedMessageToBinary(
    payloadSizeAggregator: PayloadSizeAggregator,
    m: ConsumerController.SequencedMessage[_]
  ): ReliableDelivery.SequencedMessage = {
    val payload        = m.message.asInstanceOf[WorkerTaskPB]
    val payloadBuilder = ContainerFormats.Payload.newBuilder()
    payloadBuilder.setEnclosedMessage(akka.protobufv3.internal.ByteString.copyFrom(payload.toByteArray))
    payloadBuilder.setSerializerId(serId)
    // println(s"1_MessageSent(${m.seqNr}, $serId)")

    val b = ReliableDelivery.SequencedMessage.newBuilder()
    b.setProducerId(m.producerId)
    b.setSeqNr(m.seqNr)
    b.setFirst(m.first)
    b.setAck(m.ack)
    b.setProducerControllerRef(resolver.toSerializationFormat(m.producerController))
    b.setMessage(payloadBuilder)
    val pb = b.build()

    payloadSizeAggregator.updatePayloadSize("0", pb.getSerializedSize)
    pb
  }

  def durableQueueMessageSentToProto(
    payloadSizeAggregator: PayloadSizeAggregator,
    m: DurableProducerQueue.MessageSent[_]
  ): ReliableDelivery.MessageSent = {

    val payload        = m.message.asInstanceOf[WorkerTaskPB] // .withSeqNum(m.seqNr)
    val payloadBuilder = ContainerFormats.Payload.newBuilder()
    payloadBuilder.setEnclosedMessage(akka.protobufv3.internal.ByteString.copyFrom(payload.toByteArray))
    payloadBuilder.setSerializerId(serId)

    val b = ReliableDelivery.MessageSent.newBuilder()
    b.setSeqNr(m.seqNr)
    b.setQualifier(m.confirmationQualifier)
    b.setAck(m.ack)
    b.setTimestamp(m.timestampMillis)
    b.setMessage(payloadBuilder)
    // println(s"2_MessageSent(${m.seqNr},${m.timestampMillis})")
    val pb = b.build()
    payloadSizeAggregator.updatePayloadSize("0", pb.getSerializedSize)
    pb
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

  protected def resendToBinary(m: ProducerControllerImpl.Resend): ReliableDelivery.Resend = {
    val b = ReliableDelivery.Resend.newBuilder()
    b.setFromSeqNr(m.fromSeqNr)
    b.build()
  }

  protected def registerConsumerToBinary(
    m: ProducerController.RegisterConsumer[_]
  ): ReliableDelivery.RegisterConsumer = {
    val b = ReliableDelivery.RegisterConsumer.newBuilder()
    b.setConsumerControllerRef(resolver.toSerializationFormat(m.consumerController))
    b.build()
  }

  protected def sequencedMessageFromBinary(
    directByteBuffer: ByteBuffer
  ): ConsumerController.SequencedMessage[WorkerTaskPB] = {
    val seqMsg = ReliableDelivery.SequencedMessage.parseFrom(directByteBuffer)
    val wrappedMsg = {
      val taskPbBts = seqMsg.getMessage.getEnclosedMessage.toByteArray
      WorkerTaskPB.parseFrom(taskPbBts) // .withSeqNum(seqMsg.getSeqNr) // TODO
    }

    val pb =
      ConsumerController.SequencedMessage[WorkerTaskPB](
        seqMsg.getProducerId,
        seqMsg.getSeqNr,
        wrappedMsg,
        seqMsg.getFirst,
        seqMsg.getAck
      )(resolver.resolveActorRef(seqMsg.getProducerControllerRef))

    pb
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

  protected def durableQueueStateToBinary(
    m: DurableProducerQueue.State[WorkerTaskPB],
    payloadSizeAggregator: PayloadSizeAggregator
  ): ReliableDelivery.State = {
    val b = ReliableDelivery.State.newBuilder()
    b.setCurrentSeqNr(m.currentSeqNr)
    b.setHighestConfirmedSeqNr(m.highestConfirmedSeqNr)
    b.addAllConfirmed(m.confirmedSeqNr.map { case (qualifier, (seqNr, timestamp)) =>
      durableQueueConfirmedToProto(qualifier, seqNr, timestamp)
    }.asJava)
    b.addAllUnconfirmed(m.unconfirmed.map(durableQueueMessageSentToProto(payloadSizeAggregator, _)).asJava)
    val pb = b.build()
    system.log.warning(
      "DurableProducerQueue.State: unconfirmed:{},confirmedSeqNr:{},size:{}",
      m.unconfirmed.size,
      m.confirmedSeqNr.keySet.size,
      pb.getSerializedSize
    )
    payloadSizeAggregator.updatePayloadSize("0", pb.getSerializedSize)
    pb
  }

  protected def durableQueueCleanupToBinary(m: DurableProducerQueue.Cleanup): ReliableDelivery.Cleanup = {
    val b = ReliableDelivery.Cleanup.newBuilder()
    b.addAllQualifiers(m.confirmationQualifiers.asJava)
    b.build()
  }

  protected def durableQueueStateFromBinary(buf: ByteBuffer): DurableProducerQueue.State[WorkerTaskPB] = {
    val state = ReliableDelivery.State.parseFrom(buf)
    DurableProducerQueue.State[WorkerTaskPB](
      state.getCurrentSeqNr,
      state.getHighestConfirmedSeqNr,
      state.getConfirmedList.asScala
        .map(confirmed => confirmed.getQualifier -> (confirmed.getSeqNr -> confirmed.getTimestamp))
        .toMap,
      state.getUnconfirmedList.asScala.toVector.map(durableQueueMessageSentFromProto)
    )
  }

  protected def durableQueueMessageSentFromBinary(
    directByteBuffer: ByteBuffer
  ): DurableProducerQueue.MessageSent[WorkerTaskPB] = {
    val msgSent: ReliableDelivery.MessageSent = ReliableDelivery.MessageSent.parseFrom(directByteBuffer)
    durableQueueMessageSentFromProto(msgSent)
  }

  private def durableQueueMessageSentFromProto(
    sent: ReliableDelivery.MessageSent
  ): DurableProducerQueue.MessageSent[WorkerTaskPB] = {
    val taskPbBts = sent.getMessage.getEnclosedMessage.toByteArray
    val taskPb    = WorkerTaskPB.parseFrom(taskPbBts)
    DurableProducerQueue
      .MessageSent[WorkerTaskPB](sent.getSeqNr, taskPb, sent.getAck, sent.getQualifier, sent.getTimestamp)
  }

  def durableQueueConfirmedFromBinary(directByteBuffer: ByteBuffer): DurableProducerQueue.Confirmed = {
    val confirmed = ReliableDelivery.Confirmed.parseFrom(directByteBuffer)
    DurableProducerQueue.Confirmed(confirmed.getSeqNr, confirmed.getQualifier, confirmed.getTimestamp)
  }

  def durableQueueCleanupFromBinary(directByteBuffer: ByteBuffer): DurableProducerQueue.Cleanup = {
    val cleanup = ReliableDelivery.Cleanup.parseFrom(directByteBuffer)
    DurableProducerQueue.Cleanup(cleanup.getQualifiersList.iterator.asScala.toSet)
  }

  def resendFromBinary(directByteBuffer: ByteBuffer): ProducerControllerImpl.Resend = {
    val resend = ReliableDelivery.Resend.parseFrom(directByteBuffer)
    ProducerControllerImpl.Resend(resend.getFromSeqNr)
  }

  def registerConsumerFromBinary(directByteBuffer: ByteBuffer): ProducerController.RegisterConsumer[_] = {
    val reg = ReliableDelivery.RegisterConsumer.parseFrom(directByteBuffer)
    ProducerController.RegisterConsumer(
      resolver.resolveActorRef[ConsumerController.Command[Any]](reg.getConsumerControllerRef)
    )
  }
}
