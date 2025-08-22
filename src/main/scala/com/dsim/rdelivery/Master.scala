package com.dsim.rdelivery

import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.*
import com.dsim.domain.v1.ReservationPB
import com.dsim.rdelivery.Master.Command.JobCommand

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

/** Reliable delivery: Work pulling mode (fan-out). Allows us to do things that are very similar to what kafka's
  * consumer group does. What types of things does it do:
  *
  * a) It sequences your messages to guarantee idempotency. This means we can retry, and then we can do deduplication on
  * the other side.
  *
  * b) It has flow control built-in.
  */
object Master {

  sealed trait Command

  object Command {
    final case class ReqNextWrapper(reqNext: WorkPullingProducerController.RequestNext[ReservationPB]) extends Command
    final case object Tick                                                                             extends Command
    final case object ShutDown                                                                         extends Command

    sealed trait JobCommand extends Command
    object JobCommand {
      case object Empty                         extends JobCommand
      final case class Job(task: ReservationPB) extends JobCommand
      final case class JobError(msg: String)    extends JobCommand
    }
  }

  def apply(): Behavior[Master.Command] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { implicit timer =>
        val producerController =
          ctx
            .spawn(
              WorkPullingProducerController[ReservationPB](
                producerId = "tasks",
                workerServiceKey = serviceKey,
                durableQueueBehavior = None
              ),
              "producer-controller"
            )

        producerController.tell(
          WorkPullingProducerController.Start(
            ctx.messageAdapter[WorkPullingProducerController.RequestNext[ReservationPB]](
              Command.ReqNextWrapper(_)
            )
          )
        )

        val poolPeriod = Duration.fromNanos(ctx.system.settings.config.getDuration("pool-period").toNanos) // 5.seconds
        run(None, poolPeriod)
      }
    }

  def run(
    producerController: Option[Command.ReqNextWrapper],
    poolPeriod: FiniteDuration
  )(implicit
    ctx: ActorContext[Master.Command],
    timer: TimerScheduler[Master.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case nextReq: Command.ReqNextWrapper =>
        ctx.pipeToSelf(Tables.nextReservation())(mapResult)
        run(Some(nextReq), poolPeriod)

      case pollResult: Command.JobCommand =>
        pollResult match {
          case JobCommand.Job(task) =>
            ctx.log.info(s"Got Task(${task.id}) for workers")
            producerController.foreach(_.reqNext.sendNextTo.tell(task))
            run(None, poolPeriod)

          case JobCommand.Empty =>
            ctx.log.info("Empty RS")
            timer.startSingleTimer(Command.Tick, poolPeriod)
            run(producerController, poolPeriod)

          case JobCommand.JobError(error) =>
            ctx.log.error(error)
            timer.startSingleTimer(Command.Tick, poolPeriod)
            run(producerController, poolPeriod)
        }

      case Command.Tick =>
        ctx.pipeToSelf(Tables.nextReservation())(mapResult)
        run(producerController, poolPeriod)

      case Command.ShutDown =>
        timer.startSingleTimer(Command.ShutDown, 3.seconds)
        gracefulShutdown()
    }

  def gracefulShutdown(): Behavior[Master.Command] =
    Behaviors.receiveMessage {
      case _: Command.ReqNextWrapper =>
        Behaviors.same
      case _: Command.JobCommand =>
        Behaviors.same
      case Command.Tick =>
        Behaviors.same
      case Command.ShutDown =>
        Behaviors.stopped
    }

  def mapResult[T <: Command.JobCommand]: Try[Option[UUID]] => Command.JobCommand = {
    case Success(taskOpt) =>
      val bts = new Array[Byte](1024)
      ThreadLocalRandom.current().nextBytes(bts)
      taskOpt
        .map(id =>
          Command.JobCommand
            .Job(ReservationPB(id.toString, com.google.protobuf.UnsafeByteOperations.unsafeWrap(bts)))
        )
        .getOrElse(Command.JobCommand.Empty)
    case Failure(ex) =>
      Command.JobCommand.JobError(ex.getMessage)
  }

}
