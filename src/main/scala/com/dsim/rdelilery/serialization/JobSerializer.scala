package com.dsim.rdelilery.serialization

import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

import akka.actor.ExtendedActorSystem
import com.dsim.rdelilery.{WorkMaster, Worker}
import com.sim.domain.v1.{MasterJobPB, WorkerJobPB}
import one.nio.mem.{DirectMemory, FixedSizeAllocator}
import akka.serialization.{ByteBufferSerializer, SerializerWithStringManifest}
import com.google.protobuf.{CodedInputStream, CodedOutputStream}

/**
  * https://doc.akka.io/api/akka/current/akka/serialization/ByteBufferSerializer.html
  * https://doc.akka.io/docs/akka/current/remoting-artery.html#bytebuffer-based-serialization
  */
final class JobSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with ByteBufferSerializer {

  override val identifier: Int = 999999

  val K          = 1024
  val extraSpace = 2 * K

  val maxFrameSize =
    system.settings.config.getBytes("akka.remote.artery.advanced.maximum-frame-size").toInt

  val concurrencyLevel = 1 << 16

  //Lock-free allocator that manages chunks of the fixed size.
  val allocator =
    new FixedSizeAllocator(maxFrameSize + extraSpace, (maxFrameSize + extraSpace) * concurrencyLevel)

  override def manifest(o: AnyRef): String =
    o.getClass.getName

  /**
    * Artery introduces a new serialization mechanism which allows the ByteBufferSerializer to directly write into
    * a shared java.nio.ByteBuffer instead of being forced to allocate and return an Array[Byte] for each serialized message.
    */
  override def toBinary(obj: AnyRef): Array[Byte] = {
    //println("toBinary " + obj.getClass.getName)
    //allocate a buffer in direct memory and wrap the buffer into ByteBuffer
    val address      = allocator.malloc(maxFrameSize)
    val directBuffer = DirectMemory.wrap(address, maxFrameSize)
    try {
      toBinary(obj, directBuffer)

      if (ThreadLocalRandom.current().nextDouble() > .8)
        println(s"DirectMemory: [entry:${allocator.entrySize} total:${allocator.chunkSize}]")

      directBuffer.flip()
      val bytes = new Array[Byte](directBuffer.remaining)
      directBuffer.get(bytes)
      bytes
    } finally try {
      directBuffer.clear()
      allocator.free(address)
    } catch {
      case err: Throwable ⇒
        throw new IllegalArgumentException("Allocator error", err)
    }
  }

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit =
    o match {
      case cmd: WorkMaster.Command =>
        cmd match {
          case WorkMaster.MasterJob(jobDesc) =>
            buf.put(MasterJobPB(jobDesc).toByteArray)
          case other =>
            throw new IllegalArgumentException(s"Undefined toBinary for $other")
        }
      case job: Worker.WorkerJob =>
        //WorkerJobPB(job.seqNum, job.jobDesc).writeTo(CodedOutputStream.newInstance(buf))
        buf.put(WorkerJobPB(job.seqNum, job.jobDesc).toByteArray)
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    //fromBinary(ByteBuffer.wrap(bytes), manifest)
    val address      = allocator.malloc(maxFrameSize)
    val directBuffer = DirectMemory.wrap(address, maxFrameSize)
    try {
      directBuffer.put(bytes)
      directBuffer.flip()
      fromBinary(directBuffer, manifest)
    } finally try {
      directBuffer.clear()
      allocator.free(address)
    } catch {
      case err: Throwable ⇒
        throw new IllegalArgumentException("Allocator error", err)
    }
  }

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef =
    if (manifest == classOf[WorkMaster.MasterJob].getName) {
      val pb = MasterJobPB.parseFrom(CodedInputStream.newInstance(buf))
      WorkMaster.MasterJob(pb.desc)
    } else if (manifest == classOf[Worker.WorkerJob].getName) {
      val pb = WorkerJobPB.parseFrom(CodedInputStream.newInstance(buf))
      Worker.WorkerJob(pb.seqNum, pb.desc)
    } else throw new IllegalArgumentException(s"Undefined fromBinary for $manifest")

}

/*
  if (manifest == classOf[WorkMaster.MasterJob].getName)
    WorkMaster.MasterJob(MasterJobPB.parseFrom(bytes).desc)
  else if (manifest == classOf[Worker.WorkerJob].getName) {
    val pb = WorkerJobPB.parseFrom(bytes)
    Worker.WorkerJob(pb.seqNum, pb.desc)
  } else throw new IllegalArgumentException(s"undefined fromBinary for $manifest")
 */
