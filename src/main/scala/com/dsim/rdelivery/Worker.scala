package com.dsim.rdelivery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.*
import akka.cluster.typed.Cluster
import com.dsim.domain.v1.WorkerTaskPB

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.*

object Worker {

  sealed trait Command
  object Command {
    case class DeliveryEnvelope(task: ConsumerController.Delivery[WorkerTaskPB]) extends Command
    case object WorkerGracefulShutdown                                           extends Command

    sealed trait BookingResult extends Command
    object BookingResult {
      final case class OK(correlationId: String) extends BookingResult
      final case class Error(msg: String)        extends BookingResult
    }
  }

  def apply(id: Int): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { implicit timers =>
        val settings = akka.actor.typed.delivery.ConsumerController.Settings(ctx.system)
        ctx.log.warn("★ ★ ★  Start worker{} on {} ★ ★ ★", id, Cluster(ctx.system).selfMember.address)
        ctx
          .spawn(ConsumerController(serviceKey, settings), "consumer-controller")
          .tell(
            ConsumerController.Start(
              ctx.messageAdapter[ConsumerController.Delivery[WorkerTaskPB]](Command.DeliveryEnvelope(_))
            )
          )

        run()
      }
    }

  def gracefulExit(
    task: ConsumerController.Delivery[WorkerTaskPB]
  )(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ WorkerGracefulShutdown.1  ★ ★ ★")
        Behaviors.stopped
      case res: Command.BookingResult =>
        val id = task.message.tsid
        ctx.log.warn("Completed({}) -> {} ", id, res)
        task.confirmTo.tell(ConsumerController.Confirmed)
        Behaviors.stopped
    }

  def awaitResult(
    task: ConsumerController.Delivery[WorkerTaskPB]
  )(implicit ctx: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ WorkerGracefulShutdown.0  ★ ★ ★")
        timers.startSingleTimer(Command.WorkerGracefulShutdown, 3.seconds)
        gracefulExit(task)

      case res: Command.BookingResult =>
        val id = task.message.tsid
        ctx.log.warn("Completed({}) -> {} ", id, res)

        // The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
        // while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
        // So we need to confirm to receive the next message
        task.confirmTo.tell(ConsumerController.Confirmed)
        run()
    }

  def run()(implicit ctx: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ WorkerGracefulShutdown  ★ ★ ★")
        Behaviors.stopped

      case Command.DeliveryEnvelope(task) =>
        ctx.log.warn(s"Start: ${task.message.tsid}")
        ctx.pipeToSelf(bookRooms)(mapResult)
        awaitResult(task)
    }

  def bookRooms(implicit ctx: ActorContext[Command]): Future[String] =
    akka.pattern.after(2000.millis)(Future {
      if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < .8) throw new Exception("Boom!") else "ok"
    }(ctx.executionContext))(ctx.system)

  def mapResult[T <: Command]: Try[String] => Command = {
    case Success(correlationId) => Command.BookingResult.OK(correlationId)
    case Failure(ex)            => Command.BookingResult.Error(ex.getMessage)
  }
}
