package com.dsim.rdelilery

import akka.actor.Address
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}

//producer talks with ProducerController
//Unconfirmed messages may be lost if the producer crashes. To avoid that you need to enable the durable queue on the producer side. The stored unconfirmed messages will be redelivered when the corresponding producer is started again. Those messages may be routed to different workers than before and some of them may have already been processed but the fact that they were confirmed had not been stored yet. Meaning at-least-once delivery.
object WorkMaster {

  trait Command
  final case class Job(jobDesc: String)                                                extends Command
  private case class ReqNext(r: WorkPullingProducerController.RequestNext[Worker.Job]) extends Command

  def apply(
    address: Address
  ): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      val producerControllerAdapter =
        ctx.messageAdapter[WorkPullingProducerController.RequestNext[Worker.Job]](ReqNext(_))

      val producerController = ctx.spawn(
        WorkPullingProducerController(
          producerId = "master",
          workerServiceKey = serviceKey,
          durableQueueBehavior = None
        ),
        "producer-controller"
      )
      producerController.tell(WorkPullingProducerController.Start(producerControllerAdapter))

      Behaviors.withStash(1 << 5) { implicit buf =>
        waitForNext(0)
      }
    }

  def waitForNext(n: Long)(implicit
    ctx: ActorContext[WorkMaster.Command],
    buf: StashBuffer[WorkMaster.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case job: WorkMaster.Job => //comes from outside
        if (buf.isFull) {
          ctx.log.warn("Too many requests. Producer overloaded !!!")
          Behaviors.same
        } else {
          buf.stash(job)
          Behaviors.same
        }
      case ReqNext(next) =>
        //comes from producerController when worker telling that the consume is ready to consume
        buf.unstashAll(active(n, next))
    }

  def active(
    n: Long,
    next: WorkPullingProducerController.RequestNext[Worker.Job]
  )(implicit ctx: ActorContext[WorkMaster.Command], buf: StashBuffer[WorkMaster.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Job(desc) =>
        //val resultId = java.util.UUID.randomUUID()
        //next.askNextTo
        next.sendNextTo.tell(Worker.Job(n, desc))
        waitForNext(n + 1)
      case _: ReqNext =>
        throw new IllegalStateException("Unexpected RequestNext")
    }
}
