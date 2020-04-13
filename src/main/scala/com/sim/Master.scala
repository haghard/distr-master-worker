package com.sim

import akka.actor.{ActorPath, Address}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.sim.Worker.{ScheduleTask, WProtocol}
import com.sim.http.HttpApi

import scala.concurrent.duration._

object Master {

  sealed trait MProtocol
  case object Tick                                                      extends MProtocol
  final case class TaskAck(seqNum: Long)                                extends MProtocol
  final case class GetWorkers(replyTo: ActorRef[HttpApi.Reply])         extends MProtocol
  final case class MembershipChanged(workers: Set[ActorRef[WProtocol]]) extends MProtocol

  val MasterWorker = ServiceKey[WProtocol]("master-worker")

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
      active(master, Set.empty[ActorRef[WProtocol]], ctx)
    }

  def active(
    master: Address,
    workers: Set[ActorRef[WProtocol]],
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
          replyTo.tell(HttpApi.Status(s"Master runs on: $master\nWorkers available [${paths.mkString(",")}]"))
          Behaviors.same
        case Tick =>
          workers.foreach(_.tell(ScheduleTask(counter, ctx.self)))
          active(master, workers, ctx, counter + 1L)
        case TaskAck(seqNum) =>
          ctx.log.info("TaskAck: {}", seqNum)
          Behaviors.same
      }
    }
}
