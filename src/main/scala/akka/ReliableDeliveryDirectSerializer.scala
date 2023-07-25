package akka

import akka.actor.ExtendedActorSystem
import akka.actor.typed.delivery.internal.ProducerControllerImpl
import akka.actor.typed.delivery.{ConsumerController, DurableProducerQueue, ProducerController}
import akka.cluster.ddata.protobuf.SerializationSupport
import akka.protobufv3.internal.CodedOutputStream
import akka.serialization.ByteBufferSerializer
import com.dsim.domain.v1.{JobDescriptionPB, WorkerJobPB}
import com.dsim.rdelivery.serialization.ByteBufferOutputStream
import com.dsim.rdelivery.{Master, Worker}

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import scala.util.Using
import scala.util.Using.Releasable
import ReliableDeliveryDirectSerializer._
import com.google.protobuf.CodedInputStream

object ReliableDeliveryDirectSerializer {

  implicit val releasable: Releasable[CodedOutputStream] = (r: CodedOutputStream) => r.flush()
}

/** https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
  *
  * Artery introduced a new serialization mechanism. This implementation takes advantage of new Artery serialization
  * mechanism which allows the ByteBufferSerializer to directly write into and read from a shared java.nio.ByteBuffer
  * instead of being forced to allocate and return an Array[Byte] for each serialized message.
  */
class ReliableDeliveryDirectSerializer(system: ExtendedActorSystem)
    extends akka.cluster.typed.internal.delivery.ReliableDeliverySerializer(system)
    with SerializationSupport
    with ByteBufferSerializer
    with ProtocSupport {

  // override val identifier: Int = super.identifier 36

  private val SequencedMessageManifest = "a"
  private val AckManifest              = "b"
  private val RequestManifest          = "c"

  private val ResendManifest           = "d"
  private val RegisterConsumerManifest = "e"

  private val DurableQueueMessageSentManifest = "f"
  private val DurableQueueConfirmedManifest   = "g"
  private val DurableQueueStateManifest       = "h"

  override def manifest(obj: AnyRef): String =
    obj match {
      case _: Master.JobDescription => obj.getClass.getName
      case _: Worker.WorkerJob      => obj.getClass.getName
      case _                        => super.manifest(obj)
    }

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case mCmd: Master.Command =>
        mCmd match {
          case Master.JobDescription(jobDesc) =>
            JobDescriptionPB(jobDesc).toByteArray
        }
      case wCmd: Worker.Command =>
        wCmd match {
          case Worker.WorkerJob(seqNum, jobDesc) =>
            WorkerJobPB(
              seqNum,
              com.google.protobuf.ByteString.readFrom(new ByteArrayInputStream(jobDesc))
            ).toByteArray
          case Worker.Flush =>
            throw new IllegalArgumentException("Cannot serialize Worker.Flush")
        }
      case other =>
        super.toBinary(other)
    }

  // buffer based avoiding a copy for artery
  override def toBinary(obj: AnyRef, directByteBuffer: ByteBuffer): Unit =
    obj match {
      case mCmd: Master.Command =>
        mCmd match {
          case Master.JobDescription(jobDesc) =>
            Using.resource(new ByteBufferOutputStream(directByteBuffer))(JobDescriptionPB(jobDesc).writeTo(_))
        }
      case wCmd: Worker.Command =>
        wCmd match {
          case Worker.WorkerJob(seqNum, jobDesc) =>
            Using.resource(new ByteBufferOutputStream(directByteBuffer))(
              WorkerJobPB(seqNum, com.google.protobuf.ByteString.readFrom(new ByteArrayInputStream(jobDesc)))
                .writeTo(_)
            )

          case Worker.Flush =>
            throw new IllegalArgumentException("Cannot serialize Worker.Flush")
        }

      // from akka.cluster.typed.internal.delivery.ReliableDeliverySerializer
      case m: ConsumerController.SequencedMessage[_] =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(msg.writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(sequencedMessageToBinary(m).writeTo(_))

      case m: ProducerControllerImpl.Ack =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(ackToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(ackToBinary(m).writeTo(_))

      case m: ProducerControllerImpl.Request =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(requestToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(requestToBinary(m).writeTo(_))

      case m: ProducerControllerImpl.Resend =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(resendToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(resendToBinary(m).writeTo(_))

      case m: ProducerController.RegisterConsumer[_] =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(registerConsumerToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(registerConsumerToBinary(m).writeTo(_))

      case m: DurableProducerQueue.MessageSent[_] =>
        system.log.warning("MessageSent. Confirmed:[{}:{}]", m.seqNr, m.confirmationQualifier)
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueMessageSentToProto(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(durableQueueMessageSentToProto(m).writeTo(_))
      case m: DurableProducerQueue.Confirmed =>
        system.log.warning("State. Confirmed:[{}:{}]", m.seqNr, m.confirmationQualifier)
        /*Using.resource(new ByteBufferOutputStream(directByteBuffer))(
          durableQueueConfirmedToProto(m.confirmationQualifier, m.seqNr, m.timestampMillis).writeTo(_)
        )*/
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(
          durableQueueConfirmedToProto(m.confirmationQualifier, m.seqNr, m.timestampMillis).writeTo(_)
        )
      case m: DurableProducerQueue.State[_] =>
        system.log.warning("State [{}:{}:{}]", m.currentSeqNr, m.confirmedSeqNr, m.highestConfirmedSeqNr)
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueStateToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(durableQueueStateToBinary(m).writeTo(_))
      case m: DurableProducerQueue.Cleanup =>
        // Using.resource(new ByteBufferOutputStream(directByteBuffer))(durableQueueCleanupToBinary(m).writeTo(_))
        Using.resource(CodedOutputStream.newInstance(directByteBuffer))(durableQueueCleanupToBinary(m).writeTo(_))

      case _ =>
        // goes with extra array allocation
        system.log.warning(s"toBinary ${obj.getClass.getName} ${directByteBuffer.isDirect}")
        val bytes = super.toBinary(obj)
        Using.resource(new ByteBufferOutputStream(directByteBuffer))(_.write(bytes))
    }

  override def fromBinary(directByteBuffer: ByteBuffer, manifest: String): AnyRef =
    manifest match {
      case SequencedMessageManifest =>
        val m: ConsumerController.SequencedMessage[Worker.WorkerJob] =
          sequencedMessageFromBinary[Worker.WorkerJob](directByteBuffer)
        m
      case DurableQueueStateManifest =>
        val m: DurableProducerQueue.State[Worker.WorkerJob] =
          durableQueueStateFromBinary[Worker.WorkerJob](directByteBuffer)
        system.log.warning(
          s"fromBinary: State [${m.currentSeqNr}:${m.highestConfirmedSeqNr}] Unconfirmed [${m.unconfirmed.map(_.seqNr).mkString(",")}]"
        )
        m

      case AckManifest                     => ackFromBinary(directByteBuffer)
      case RequestManifest                 => requestFromBinary(directByteBuffer)
      case DurableQueueMessageSentManifest => durableQueueMessageSentFromBinary(directByteBuffer)
      case DurableQueueConfirmedManifest   => durableQueueConfirmedFromBinary(directByteBuffer)
      case ResendManifest                  => resendFromBinary(directByteBuffer)
      case RegisterConsumerManifest        => registerConsumerFromBinary(directByteBuffer)

      // TODO: support more messages
      case other =>
        // system.log.warning(s"fromBinary: [$manifest]")

        if (other == classOf[Master.JobDescription].getName) {
          // JobDescriptionPB.parseFrom(new ByteBufferInputStream(directByteBuffer))
          //
          val pb = JobDescriptionPB.parseFrom(com.google.protobuf.CodedInputStream.newInstance(directByteBuffer))
          Master.JobDescription(pb.desc)
        } else if (other == classOf[Worker.WorkerJob].getName) {

          // val in = CodedInputStream.newInstance(directByteBuffer)
          // Worker.WorkerJob(in.readInt64(), in.readByteArray())

          val pb = WorkerJobPB.parseFrom(CodedInputStream.newInstance(directByteBuffer))
          // val pb = WorkerJobPB.parseFrom(new ByteBufferInputStream(directByteBuffer))
          Worker.WorkerJob(pb.seqNum, pb.desc.toByteArray)
        } else {
          system.log.warning(s"fromBinary $manifest : ${directByteBuffer.isDirect}")
          // allocate extra array
          val bytes = new Array[Byte](directByteBuffer.remaining)
          directByteBuffer.get(bytes)
          super.fromBinary(bytes, manifest)
        }
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinary(ByteBuffer.wrap(bytes), manifest)
}
