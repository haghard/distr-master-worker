package com
package dsim

import akka.actor.{ActorPath, Address}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import Worker.{ScheduleTask, WProtocol}
import http.HttpApi

import scala.concurrent.duration._

object Master {

  sealed trait MProtocol
  case object Tick                                                      extends MProtocol
  final case class TaskAck(seqNum: Long)                                extends MProtocol
  final case class GetWorkers(replyTo: ActorRef[http.HttpApi.Reply])    extends MProtocol
  final case class MembershipChanged(workers: Set[ActorRef[WProtocol]]) extends MProtocol

  def apply(master: Address): Behavior[MProtocol] =
    Behaviors.setup { ctx =>
      ctx.log.warn("Start master on {}", master)

      ctx.system.receptionist ! Receptionist.Subscribe(
        MasterWorkerKey,
        ctx.messageAdapter[Receptionist.Listing] {
          case MasterWorkerKey.Listing(workers) =>
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
          //Add associated ip address to local worker to make it look the same as the remote workers look
          val localWorkerPath = ActorPath.fromString(
            s"akka://${Runner.SystemName}@${master.host.get}:${master.port.get}/${localWorker.path.elements.mkString("/")}"
          )
          val paths = remote.map(_.path) + localWorkerPath
          replyTo.tell(HttpApi.Status(master.toString, paths.map(_.toString).toList))
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
