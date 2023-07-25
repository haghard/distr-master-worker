package com
package dsim

import Master.{Protocol, TaskAck}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

object Worker {

  sealed trait WProtocol
  final case class ScheduleTask(seqNum: Long, replyTo: ActorRef[Protocol]) extends WProtocol

  def apply(address: String, master: ActorRef[Master.Protocol]): Behavior[WProtocol] =
    Behaviors.setup { ctx =>
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(MasterWorkerKey, ctx.self)
      ctx.log.warn("★ ★ ★ Started worker {} in [idle] state waiting for commands from master {} ★ ★ ★", address, master)
      idle(address, master, ctx)
    }

  def idle(
    workerAddr: String,
    master: ActorRef[Master.Protocol],
    ctx: ActorContext[WProtocol]
  ): Behavior[WProtocol] =
    Behaviors.receiveMessage { case ScheduleTask(seqNum, replyTo) =>
      ctx.log.info("Worker {} gets task {}", workerAddr, seqNum)
      // if (java.util.concurrent.ThreadLocalRandom.current().nextDouble < .4 && !addr.contains("2551")) throw new Exception("Boom !!!")
      replyTo.tell(TaskAck(seqNum))
      Behaviors.same
    }
}
