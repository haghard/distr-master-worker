package com.dsim.rdelivery

import akka.TsidUtils
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.WorkPullingProducerController
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.cluster.UniqueAddress
import akka.persistence.typed.PersistenceId
import com.dsim.domain.v1.WorkerTaskPB
import com.dsim.rdelivery.Master.Command.JobCommand
import com.dsim.rdelivery.Master.Command.JobCommand.*
import io.hypersistence.tsid.TSID

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/** Reliable delivery: Work pulling mode (fan-out) Allows us to do things that are very similar to what kafka's consumer
  * group does.
  *
  * Six things you can do in Akka 2.6 by Chris Batey https://youtu.be/85FW9EUOixg?t=772
  *
  * Unconfirmed messages may be lost if the producer crashes. To avoid that you need to enable the durable queue on the
  * producer side. https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#durable-producer
  *
  * The stored unconfirmed messages will be redelivered when the corresponding producer is started again. Those messages
  * may be routed to different workers than before and some of them may have already been processed but the fact that
  * they were confirmed had not been stored yet.
  *
  * All this stuff about messages being idempotent, retried and redelivered.
  *
  * What types of things does it do:
  *
  * a) It sequences your messages to guarantee idempotency. This means we can retry and then we can do deduplication on
  * the other side.
  *
  * b) It has flow control build in (to support more than just a req/resp flow).
  */
object Master {

  sealed trait Command

  object Command {
    final case class ReqNextWrapper(reqNext: WorkPullingProducerController.RequestNext[WorkerTaskPB]) extends Command
    case object Tick                                                                                  extends Command
    case object ShutDown                                                                              extends Command

    sealed trait JobCommand extends Command
    object JobCommand {
      final case class Job(tasks: Seq[WorkerTaskPB]) extends JobCommand
      final case class JobError(msg: String)         extends JobCommand
    }
  }

  val idGen = new AtomicLong()

  def apply(uniqueAddress: UniqueAddress): Behavior[Master.Command] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { implicit timer =>
        // https://github.com/akka/akka-persistence-jdbc/blob/v5.0.4/core/src/main/resources/schema/mysql/mysql-create-schema.sql
        /** Durable producer. Until sent messages have been confirmed, the producer side keeps them in memory to be able
          * to resend them. If the JVM of the producer side crashes those unconfirmed messages are lost. To make sure
          * the messages can be delivered also in that scenario a DurableProducerQueue can be used. Then the unconfirmed
          * messages are stored in a durable way so that they can be redelivered when the producer is started again.
          */
        // val durableQueue = akka.persistence.typed.delivery.EventSourcedProducerQueue[WorkerTaskPB](PersistenceId.ofUniqueId("tasks"))

        ctx
          .spawn(
            WorkPullingProducerController[WorkerTaskPB](
              producerId = "tasks",
              workerServiceKey = serviceKey,
              durableQueueBehavior = None // Some(durableQueue)
            ),
            "producer-controller"
          )
          .tell(
            WorkPullingProducerController.Start(
              ctx.messageAdapter[WorkPullingProducerController.RequestNext[WorkerTaskPB]](
                Command.ReqNextWrapper(_)
              )
            )
          )

        // https://vladmihalcea.com/uuid-database-primary-key/
        /*val nodeCount   = 20
        val tsIdFactory = TsidUtils.getTsidFactory(nodeCount, (uniqueAddress.longUid % nodeCount).abs.toInt)*/
        run(None)
      }
    }

  private val limit = 0.7

  /*def idle(fact: TSID.Factory, req: Command.ReqNextWrapper)(implicit
    ctx: ActorContext[Master.Command],
    timer: TimerScheduler[Master.Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case nextReq: Command.ReqNextWrapper =>
        println(s"--------------ANOTHER req: " + nextReq.reqNext)
        Behaviors.same
      case Command.Tick =>
        ctx.log.error("TICK: {}", req.reqNext)
        if (ThreadLocalRandom.current().nextDouble() < limit)
          idle(fact, req)
        else {
          timer.cancel(Command.Tick)
          ctx.self.tell(req)
          run(fact)
        }
      case Command.ShutDown =>
        timer.cancel(Command.Tick)
        Behaviors.stopped
    }

  def await(req: Command.ReqNextWrapper): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case next: Command.ReqNextWrapper =>
        ???
      case res: Command.JobCommand =>
        ???
    }
  }*/

  val poolTimeout = 10.seconds

  def run(
    pendingReq: Option[Command.ReqNextWrapper]
  )(implicit ctx: ActorContext[Master.Command], timer: TimerScheduler[Master.Command]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case nextReq: Command.ReqNextWrapper =>
        ctx.pipeToSelf(pollNext)(mapResult)
        if (pendingReq.isDefined) {
          println(s"${nextReq.reqNext.sendNextTo} ----VS---- ${pendingReq.get.reqNext.sendNextTo}")
        }
        run(Some(nextReq))

      case pollResult: Command.JobCommand =>
        pollResult match {
          case JobCommand.Job(tasks) =>
            tasks.headOption match {
              case Some(task) =>
                pendingReq.foreach(_.reqNext.sendNextTo.tell(task))
              case None =>
                ctx.log.debug("-------------Empty RS----------")
                timer.startSingleTimer(Command.Tick, poolTimeout)
            }

          case JobCommand.JobError(error) =>
            ctx.log.error(error)
            timer.startSingleTimer(Command.Tick, poolTimeout)
        }
        run(pendingReq)

      case Command.Tick =>
        ctx.log.debug("-------------Tick----------")
        ctx.pipeToSelf(pollNext)(mapResult)
        run(pendingReq)

      case Command.ShutDown =>
        timer.startSingleTimer(Command.ShutDown, 3.seconds)
        Behaviors.receiveMessagePartial { case Command.ShutDown => Behaviors.stopped }
    }

  // factory: TSID.Factory
  def pollNext()(implicit ctx: ActorContext[Master.Command]): Future[Job] =
    if (ThreadLocalRandom.current().nextDouble() < limit) {
      Future {

        /*
          Take unfulfilled hotel reservations that are close to their reservation date
          ensuring that the shopping and ranking rules for the program are satisfied
         */
        val id  = idGen.getAndDecrement()
        val bts = new Array[Byte](1024)
        ThreadLocalRandom.current().nextBytes(bts)
        val task = WorkerTaskPB(id, com.google.protobuf.UnsafeByteOperations.unsafeWrap(bts))
        Job(Seq(task))
      }(ctx.executionContext)
    } else {
      Future.successful(Job(Seq.empty))
    }

  def mapResult[T <: Command.JobCommand]: Try[Job] => Command.JobCommand = {
    case Success(job) =>
      job
    case Failure(ex) =>
      Command.JobCommand.JobError(ex.getMessage)
  }

}
