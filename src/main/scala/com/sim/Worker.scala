package com.sim

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.sim.Master.{MProtocol, Status, TaskScheduled}

object Worker {

  sealed trait WorkDistribution
  case class GetWorkerInfo(replyTo: ActorRef[MProtocol])              extends WorkDistribution
  case class ScheduleTask(seqNum: Long, replyTo: ActorRef[MProtocol]) extends WorkDistribution

  def apply(workerAddress: String): Behavior[WorkDistribution] =
    Behaviors.setup { ctx =>
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(Master.MasterWorker, ctx.self)

      Behaviors.receiveMessage {
        case GetWorkerInfo(replyTo) =>
          replyTo.tell(Status(workerAddress))
          Behaviors.same
        case ScheduleTask(seqNum, replyTo) =>
          ctx.log.info("ScheduleTask {}", seqNum)
          replyTo.tell(TaskScheduled(seqNum))
          Behaviors.same
      }
    }

}
