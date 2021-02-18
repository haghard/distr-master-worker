package com.dsim.rdelivery

import akka.actor.Address
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.adapter.{TypedActorContextOps, TypedActorSystemOps}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.serialization.SerializationExtension
import akka.util.ByteString

import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

//producer talks to ProducerController

/**  Reliable delivery: Work pulling mode (fan-out) Allows us to do thinks that are very simular to what kafka's consumer group
  *
  * Six things you can do in Akka 2.6 by Chris Batey  https://youtu.be/85FW9EUOixg?t=772
  *
  * Unconfirmed messages may be lost if the producer crashes. To avoid that you need to enable the durable queue on the producer side.
  * https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#durable-producer
  *
  * The stored unconfirmed messages will be redelivered when the corresponding producer is started again. Those messages may be routed to different
  * workers than before and some of them may have already been processed but the fact that they were confirmed had not been stored yet.
  *
  * All this stuff about messages being idempotent, retried and redelivered.
  *
  * What types of things does it do:
  *  a) It sequence your messages to guarantee idempotency. This means we can retry and then we can do deduplication on the other side.
  *  b) It has flow control build to it(not a simple req/resp).
  */
object Master {

  sealed trait Command
  final case class JobDescription(jobDesc: String)                                                   extends Command
  private final case class PullNext(rn: WorkPullingProducerController.RequestNext[Worker.WorkerJob]) extends Command

  def apply(
    address: Address
  ): Behavior[Master.Command] =
    Behaviors.setup { implicit ctx =>
      val producerControllerAdapter =
        ctx.messageAdapter[WorkPullingProducerController.RequestNext[Worker.WorkerJob]](PullNext(_))

      //val serialization = SerializationExtension(ctx.system.toClassic)
      //val serializer = serialization.serializerFor(classOf[akka.actor.typed.delivery.ConsumerController.SequencedMessage[_]])
      //ctx.log.error("*** {}:{} ****", serializer.identifier, serializer.getClass.getName)

      ctx
        .spawn(
          WorkPullingProducerController(
            producerId = "master",
            workerServiceKey = serviceKey,
            durableQueueBehavior = None
          ),
          "producer-controller"
        )
        .tell(WorkPullingProducerController.Start(producerControllerAdapter))

      val cfg = ctx.system.settings.config.getConfig("akka.reliable-delivery")
      val bufferSize =
        cfg.getInt("work-pulling.producer-controller.buffer-size") +
        cfg.getInt("consumer-controller.flow-control-window")

      Behaviors.withStash(bufferSize)(implicit buf => idle(0))
    }

  def idle(seqNum: Long)(implicit
    ctx: ActorContext[Master.Command],
    buf: StashBuffer[Master.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case job: Master.JobDescription => //comes from outside
        if (buf.isFull) ctx.log.warn("Too many requests. Master.Producer overloaded !!!")
        else buf.stash(job)
        Behaviors.same

      case PullNext(next) => //comes from producerController when worker telling that the consume is ready to consume
        buf.unstashAll(active(seqNum, next))
    }

  def active(
    seqNum: Long,
    next: WorkPullingProducerController.RequestNext[Worker.WorkerJob]
  )(implicit ctx: ActorContext[Master.Command], buf: StashBuffer[Master.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Master.JobDescription(desc) =>
        val b = new Array[Byte](1024 * 33)
        ThreadLocalRandom.current().nextBytes(b)
        val job = Worker.WorkerJob(seqNum, b)
        ctx.log.warn(s"Produced: ${job.seqNum}")

        next.sendNextTo.tell(job)
        //next.askNextTo

        idle(seqNum + 1)
      case _: PullNext =>
        ctx.log.error("Unexpected Demand. Stop the Master")
        Behaviors.stopped
      //throw new IllegalStateException("Unexpected Demand")
    }
}
