package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.delivery.{ConsumerController, DurableProducerQueue, ProducerController}
import akka.cluster.ddata.protobuf.SerializationSupport
import akka.serialization.ByteBufferSerializer
import com.dsim.domain.v1.{JobDescriptionPB, WorkerJobPB}
import com.dsim.rdelivery.serialization.{ByteBufferInputStream, ByteBufferOutputStream}
import com.dsim.rdelivery.{Master, Worker}

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import scala.util.Using

/** https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
  *
  * Artery introduced a new serialization mechanism.
  * This implementation takes advantage of new Artery serialization mechanism
  * which allows the ByteBufferSerializer to directly write into and read from a shared java.nio.ByteBuffer
  * instead of being forced to allocate and return an Array[Byte] for each serialized message.
  */
final class DataSerializer(system: ExtendedActorSystem)
    extends akka.cluster.typed.internal.delivery.ReliableDeliverySerializer(system)
    with SerializationSupport
    with ByteBufferSerializer
    with ProtocSupport {

  //override val identifier: Int = super.identifier 36

  override def manifest(obj: AnyRef): String =
    obj match {
      case _: Master.JobDescription ⇒ obj.getClass.getName
      case _: Worker.WorkerJob      ⇒ obj.getClass.getName
      case _                        ⇒ super.manifest(obj)
    }

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case Master.JobDescription(desc) ⇒ JobDescriptionPB(desc).toByteArray
      case job: Worker.WorkerJob ⇒
        WorkerJobPB(
          job.seqNum,
          com.google.protobuf.ByteString.readFrom(new ByteArrayInputStream(job.jobDesc))
        ).toByteArray
      case other ⇒ super.toBinary(other)
    }

  override def toBinary(o: AnyRef, directByteBuffer: ByteBuffer): Unit =
    o match {
      case Master.JobDescription(desc) ⇒
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(JobDescriptionPB(desc).writeTo(_))
      case job: Worker.WorkerJob ⇒
        //com.google.protobuf.ByteString.copyFrom(???)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(
          WorkerJobPB(job.seqNum, com.google.protobuf.ByteString.readFrom(new ByteArrayInputStream(job.jobDesc)))
            .writeTo(_)
        )
      // from akka.cluster.typed.internal.delivery.ReliableDeliverySerializer
      case m: ConsumerController.SequencedMessage[_] ⇒
        val msg = sequencedMessageToBinary(m)
        /*
        msg.getProducerId
        msg.getSeqNr
        msg.getMessage.getMessageManifest.toStringUtf8
        msg.getMessage.getSerializedSize
         */

        system.log.warning(
          "toBinary: SequencedMessage:{} Sizes:[{}:{}] IsDirect:{}",
          msg.getMessage.getMessageManifest.toStringUtf8,
          msg.getSerializedSize,
          msg.getMessage.getSerializedSize,
          directByteBuffer.isDirect
        )
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(msg.writeTo(_))
      case m: ProducerControllerImpl.Ack ⇒
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(ackToBinary(m).writeTo(_))
      case m: ProducerControllerImpl.Request ⇒
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(requestToBinary(m).writeTo(_))
      case m: ProducerControllerImpl.Resend ⇒
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(resendToBinary(m).writeTo(_))
      case m: ProducerController.RegisterConsumer[_] ⇒
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(registerConsumerToBinary(m).writeTo(_))
      //akka.cluster.typed.internal.delivery.ReliableDeliverySerializer
      case m: DurableProducerQueue.MessageSent[_] ⇒
        system.log.warning("MessageSent. Confirmed:[{}:{}]", m.seqNr, m.confirmationQualifier)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueMessageSentToProto(m).writeTo(_))
      case m: DurableProducerQueue.Confirmed ⇒
        system.log.warning("State. Confirmed:[{}:{}]", m.seqNr, m.confirmationQualifier)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(
          durableQueueConfirmedToProto(m.confirmationQualifier, m.seqNr, m.timestampMillis).writeTo(_)
        )
      case m: DurableProducerQueue.State[_] ⇒
        system.log.warning("State [{}:{}:{}]", m.currentSeqNr, m.confirmedSeqNr, m.highestConfirmedSeqNr)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueStateToBinary(m).writeTo(_))
      case m: DurableProducerQueue.Cleanup ⇒
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueCleanupToBinary(m).writeTo(_))
      /*case _ ⇒
        goes with extra array allocation
        val bytes = super.toBinary(o)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(_.write(bytes))
       */
    }

  // byteBuffer -> obj
  override def fromBinary(byteBuffer: ByteBuffer, manifest: String): AnyRef =
    if (manifest == classOf[Master.JobDescription].getName) {
      val pb = JobDescriptionPB.parseFrom(new ByteBufferInputStream(byteBuffer))
      //val pb = JobDescriptionPB.parseFrom(com.google.protobuf.CodedInputStream.newInstance(directByteBuffer))
      Master.JobDescription(pb.desc)
    } else if (manifest == classOf[Worker.WorkerJob].getName) {
      val pb = WorkerJobPB.parseFrom(new ByteBufferInputStream(byteBuffer))
      Worker.WorkerJob(pb.seqNum, pb.desc.toByteArray)
    } else if (manifest == "a" /*super.SequencedMessageManifest*/ ) {
      val m = sequencedMessageFromBinary[Worker.WorkerJob](byteBuffer)
      system.log.warning(
        "fromBinary: SequencedMessage Payload-size: {}. IsDirect:{}",
        m.message.asInstanceOf[Worker.WorkerJob].jobDesc.size,
        byteBuffer.isDirect
      )
      m
    } else if (manifest == "h") { //super.DurableQueueStateManifest = "h"
      val m = durableQueueStateFromBinary[Worker.WorkerJob](byteBuffer)
      system.log.warning(
        s"fromBinary: State [${m.currentSeqNr}:${m.highestConfirmedSeqNr}] Unconfirmed [${m.unconfirmed.map(_.seqNr).mkString(",")}]"
      )
      m
    } else {
      //allocate extra array
      val bytes = new Array[Byte](byteBuffer.remaining)
      byteBuffer.get(bytes)
      super.fromBinary(bytes, manifest)
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)

  /*

  val maxFrameSize = system.settings.config.getBytes("akka.remote.artery.advanced.maximum-frame-size").toInt
  val bufferPool   = new akka.io.DirectByteBufferPool(defaultBufferSize = maxFrameSize, maxPoolEntries = 1 << 5)

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val directByteBuffer = bufferPool.acquire()
    try {
      directByteBuffer.put(bytes)
      directByteBuffer.flip()
      fromBinary(directByteBuffer, manifest)
    } catch {
      case ex: java.nio.BufferOverflowException ⇒
        throw new IllegalArgumentException(
          s"There is insufficient space in this buffer. MaxFrameSize:$maxFrameSize - Bytes: ${bytes.size}",
          ex
        )
      case other ⇒
        throw new IllegalArgumentException(s"Can't deserialize bytes§ in [${this.getClass.getName}]", other)
    } finally bufferPool.release(directByteBuffer)
  }
   */

}
