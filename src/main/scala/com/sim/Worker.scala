package com.sim

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.sim.Master.{MProtocol, TaskAck}

object Worker {

  sealed trait WProtocol
  final case class ScheduleTask(seqNum: Long, replyTo: ActorRef[MProtocol]) extends WProtocol

  def apply(addr: String): Behavior[WProtocol] =
    Behaviors.setup { ctx =>
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(Master.MasterWorker, ctx.self)

      Behaviors.receiveMessage {
        case ScheduleTask(seqNum, replyTo) =>
          ctx.log.info("Worker {} gets task {}", addr, seqNum)
          replyTo.tell(TaskAck(seqNum))
          Behaviors.same
      }
    }

}
