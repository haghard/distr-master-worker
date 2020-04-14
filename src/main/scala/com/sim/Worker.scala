package com
package sim

import java.util.concurrent.ThreadLocalRandom

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import Master.{MProtocol, TaskAck}

object Worker {

  sealed trait WProtocol
  final case class ScheduleTask(seqNum: Long, replyTo: ActorRef[MProtocol]) extends WProtocol

  def apply(addr: String): Behavior[WProtocol] =
    Behaviors.setup { ctx =>
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(MasterWorkerKey, ctx.self)

      Behaviors.receiveMessage {
        case ScheduleTask(seqNum, replyTo) =>
          ctx.log.info("Worker {} gets task {}", addr, seqNum)
          //if (ThreadLocalRandom.current().nextDouble < .4 && !addr.contains("2551")) throw new Exception("Boom !!!")

          replyTo.tell(TaskAck(seqNum))
          Behaviors.same
      }
    }

}
