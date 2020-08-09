package com.dsim.rdelilery

import akka.actor.Address
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}

//producer talks with ProducerController

/**
  *  Reliable delivery: Work pulling mode (fan-out) Allows us to do thinks that are very simular to what kafka's consumer group
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
object WorkMaster {

  sealed trait Command
  final case class MasterJob(jobDesc: String)                                                     extends Command
  private final case class Demand(r: WorkPullingProducerController.RequestNext[Worker.WorkerJob]) extends Command

  def apply(
    address: Address
  ): Behavior[WorkMaster.Command] =
    Behaviors.setup { implicit ctx =>
      val producerControllerAdapter =
        ctx.messageAdapter[WorkPullingProducerController.RequestNext[Worker.WorkerJob]](Demand(_))

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

      Behaviors.withStash(bufferSize) { implicit buf =>
        waitForDemand(0)
      }
    }

  def waitForDemand(seqNum: Long)(implicit
    ctx: ActorContext[WorkMaster.Command],
    buf: StashBuffer[WorkMaster.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case job: WorkMaster.MasterJob => //comes from outside
        if (buf.isFull) {
          ctx.log.warn("Too many requests. Master.Producer overloaded !!!")
          Behaviors.same
        } else {
          buf.stash(job)
          Behaviors.same
        }
      case Demand(next) =>
        //comes from producerController when worker telling that the consume is ready to consume
        buf.unstashAll(active(seqNum, next))
    }

  def active(
    seqNum: Long,
    next: WorkPullingProducerController.RequestNext[Worker.WorkerJob]
  )(implicit ctx: ActorContext[WorkMaster.Command], buf: StashBuffer[WorkMaster.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case WorkMaster.MasterJob(desc) =>
        //val resultId = java.util.UUID.randomUUID()
        //next.askNextTo
        val job = Worker.WorkerJob(seqNum, desc)
        ctx.log.warn(s"Master produced: $job")
        next.sendNextTo.tell(job)
        waitForDemand(seqNum + 1)
      case _: Demand =>
        throw new IllegalStateException("Unexpected Demand")
    }
}
