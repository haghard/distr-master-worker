package com.sim

import akka.actor.{ActorPath, Address}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.sim.Worker.{ScheduleTask, WorkDistribution}

import scala.concurrent.duration._

object Master {

  sealed trait MProtocol
  case object Tick                       extends MProtocol
  case class TaskScheduled(seqNum: Long) extends MProtocol

  sealed trait Request                                                   extends MProtocol
  case class GetWorkers(replyTo: ActorRef[Reply])                        extends Request
  case class MembershipChanged(workers: Set[ActorRef[WorkDistribution]]) extends Request

  sealed trait Reply              extends MProtocol
  case class Status(desc: String) extends Reply

  val MasterWorker = ServiceKey[WorkDistribution]("master-worker")

  def apply(master: Address): Behavior[MProtocol] =
    Behaviors.setup { ctx =>
      ctx.log.warn("Start master on {}", master)

      ctx.system.receptionist ! Receptionist.Subscribe(
        Master.MasterWorker,
        ctx.messageAdapter[Receptionist.Listing] {
          case Master.MasterWorker.Listing(workers) =>
            MembershipChanged(workers)
        }
      )
      active(master, Set.empty[ActorRef[WorkDistribution]], ctx)
    }

  def active(
    master: Address,
    workers: Set[ActorRef[WorkDistribution]],
    ctx: ActorContext[MProtocol],
    counter: Long = 0L
  ): Behavior[MProtocol] =
    Behaviors.withTimers { timer =>
      timer.startTimerAtFixedRate(Tick, 5.seconds)

      Behaviors.receiveMessage {
        case MembershipChanged(workers) =>
          ctx.log.warn("Membership changed: {}", workers.map(_.path).mkString(","))
          active(master, workers, ctx, counter)
        case GetWorkers(replyTo) =>
          val localWorker = workers.filter(_.path.address.host.isEmpty).head
          val remote      = workers - localWorker
          //Add ip address to local worker to loop prettier
          val localWorkerPath = ActorPath.fromString(
            s"akka://${Runner.SystemName}@${master.host.get}:${master.port.get}/${localWorker.path.elements.mkString("/")}"
          )
          val paths = remote.map(_.path) + localWorkerPath
          replyTo.tell(Status(s"Master runs on: $master\nWorkers available [${paths.mkString(",")}]"))
          Behaviors.same
        case Tick =>
          workers.foreach(_.tell(ScheduleTask(counter, ctx.self)))
          active(master, workers, ctx, counter + 1L)
        case TaskScheduled(seqNum) =>
          ctx.log.info("TaskScheduled: {}", seqNum)
          Behaviors.same
        case other =>
          ctx.log.warn("Unexpected msg {}", other)
          Behaviors.stopped
      }
    }
}
