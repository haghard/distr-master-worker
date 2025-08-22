package com.dsim.rdelivery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.*
import akka.cluster.typed.Cluster
import com.dsim.domain.v1.ReservationPB

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.*
import scala.util.control.NonFatal

object Worker {

  sealed trait Command
  object Command {
    case class DeliveryEnvelope(task: ConsumerController.Delivery[ReservationPB]) extends Command
    case object WorkerGracefulShutdown                                            extends Command

    sealed trait BookingResult extends Command
    object BookingResult {
      final case class OK(correlationId: String) extends BookingResult
      final case class Error(msg: String)        extends BookingResult
    }
  }

  def apply(workerId: Int): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { implicit timers =>
        val settings = akka.actor.typed.delivery.ConsumerController.Settings(ctx.system)
        ctx.log.warn("★ ★ ★  Start worker{} on {} ★ ★ ★", workerId, Cluster(ctx.system).selfMember.address)
        val consumerController = ctx.spawn(ConsumerController(serviceKey, settings), "consumer-controller")

        consumerController.tell(
          ConsumerController.Start(
            ctx.messageAdapter[ConsumerController.Delivery[ReservationPB]](Command.DeliveryEnvelope(_))
          )
        )

        run()
      }
    }

  def gracefulShutdown(
    task: ConsumerController.Delivery[ReservationPB]
  )(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ Stop WorkerGracefulShutdown.1  ★ ★ ★")
        Behaviors.stopped
      case res: Command.BookingResult =>
        val id = task.message.id
        ctx.log.warn("Completed({}) -> {} ", id, res)
        task.confirmTo.tell(ConsumerController.Confirmed)
        Behaviors.stopped
      case Command.DeliveryEnvelope(_) =>
        // do not accept new tasks here
        Behaviors.same
    }

  def awaitResult(
    task: ConsumerController.Delivery[ReservationPB]
  )(implicit ctx: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ Shutting down WorkerGracefulShutdown.0  ★ ★ ★")
        timers.startSingleTimer(Command.WorkerGracefulShutdown, 2.seconds)
        gracefulShutdown(task)

      case res: Command.BookingResult =>
        ctx.log.warn("Completed({}) -> {} ", task.message.id, res)

        // The next message is not delivered until the previous one is confirmed. Any messages from the producer that arrive
        // while waiting for the confirmation are stashed by the ConsumerController and delivered when the previous message is confirmed.
        // So we need to confirm to receive the next message.
        task.confirmTo.tell(ConsumerController.Confirmed)
        run()
    }

  def run()(implicit ctx: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Command.WorkerGracefulShutdown =>
        ctx.log.warn("★ ★ ★ WorkerGracefulShutdown  ★ ★ ★")
        Behaviors.stopped

      case Command.DeliveryEnvelope(task) =>
        ctx.log.warn(s"Start(${task.message.id})")
        // task.seqNr can be used for deduplication
        ctx.pipeToSelf(bookRooms(UUID.fromString(task.message.id))(ctx.executionContext))(mapResult)
        awaitResult(task)
    }

  def bookRooms(id: UUID)(implicit ec: ExecutionContext): Future[String] =
    Future {
      // grpcClint.book()
      Thread.sleep(1_500)
      if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > .95) throw new Exception("Boom!") else "done"
    }.flatMap(response => Tables.markDone(id).map(_ => response))
      .recoverWith { case NonFatal(ex) =>
        ex.getMessage
        Tables.markFailed(id).map(_ => "error")
      }

  def mapResult[T <: Command]: Try[String] => Command = {
    case Success(correlationId) => Command.BookingResult.OK(correlationId)
    case Failure(ex)            => Command.BookingResult.Error(ex.getMessage)
  }
}
