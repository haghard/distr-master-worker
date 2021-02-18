package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.delivery.ConsumerController
import akka.cluster.ddata.protobuf.SerializationSupport
import akka.serialization.ByteBufferSerializer
import com.dsim.domain.v1.{JobDescriptionPB, WorkerJobPB}
import com.dsim.rdelivery.serialization.ByteBufferOutputStream
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
      case _: Master.JobDescription => obj.getClass.getName
      case _: Worker.WorkerJob      => obj.getClass.getName
      case _                        => super.manifest(obj)
    }

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case Master.JobDescription(desc) => JobDescriptionPB(desc).toByteArray
      case job: Worker.WorkerJob =>
        WorkerJobPB(
          job.seqNum,
          com.google.protobuf.ByteString.readFrom(new ByteArrayInputStream(job.jobDesc))
        ).toByteArray
      case other => super.toBinary(other)
    }

  override def toBinary(o: AnyRef, directByteBuffer: ByteBuffer): Unit =
    o match {
      case Master.JobDescription(desc) =>
        //system.log.warning("toBinary: JobDescription {}", buf.hasArray)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(JobDescriptionPB(desc).writeTo(_))
      case job: Worker.WorkerJob =>
        //system.log.warning("toBinary: WorkerJob {}", buf.hasArray)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(
          WorkerJobPB(job.seqNum, com.google.protobuf.ByteString.readFrom(new ByteArrayInputStream(job.jobDesc)))
            .writeTo(_)
        )
      // from akka.cluster.typed.internal.delivery.ReliableDeliverySerializer
      case m: ConsumerController.SequencedMessage[_] =>
        val msg = sequencedMessageToBinary(m)

        msg.getProducerId
        msg.getSeqNr
        msg.getMessage.getSerializedSize

        system.log.warning(
          "toBinary: SequencedMessage. Size:[{}:{}]",
          //directByteBuffer.hasArray,
          msg.getSerializedSize,
          msg.getMessage.getSerializedSize
        )
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(msg.writeTo(_))
      case _ ⇒
        // others from akka.cluster.typed.internal.delivery.ReliableDeliverySerializer
        val bytes = super.toBinary(o)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(_.write(bytes))
    }

  override def fromBinary(directByteBuffer: ByteBuffer, manifest: String): AnyRef =
    if (manifest == classOf[Master.JobDescription].getName) {
      val pb = JobDescriptionPB.parseFrom(com.google.protobuf.CodedInputStream.newInstance(directByteBuffer))
      Master.JobDescription(pb.desc)
    } else if (manifest == classOf[Worker.WorkerJob].getName) {
      val pb = WorkerJobPB.parseFrom(com.google.protobuf.CodedInputStream.newInstance(directByteBuffer))
      Worker.WorkerJob(pb.seqNum, pb.desc.toByteArray)
    } else if (manifest == "a" /*super.SequencedMessageManifest*/ ) {
      val m = sequencedMessageFromBinary(directByteBuffer)
      system.log.warning(
        "fromBinary: SequencedMessage Payload-size: {}",
        m.message.asInstanceOf[Worker.WorkerJob].jobDesc.size
      )
      m
    } else {
      //allocate extra array
      val bytes = new Array[Byte](directByteBuffer.remaining)
      directByteBuffer.get(bytes)
      super.fromBinary(bytes, manifest)
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)
}
