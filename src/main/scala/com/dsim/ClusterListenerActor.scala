package com.dsim

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ClusterEvent._
import akka.cluster.typed.{Cluster, Subscribe}

//https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-4-the-source-code
object ClusterListenerActor {

  def apply(): Behavior[ClusterDomainEvent] =
    Behaviors.setup[ClusterDomainEvent] { ctx =>
      val cluster = Cluster(ctx.system)
      cluster.subscriptions.tell(Subscribe(ctx.self.ref, classOf[ClusterDomainEvent]))

      ctx.log.info(s"Started ${ctx.self.path} - (${ctx.self.getClass})")

      def active(): Behavior[ClusterDomainEvent] =
        Behaviors.receiveMessagePartial {
          case MemberUp(member) =>
            ctx.log.info("Member is Up: {}", member.address)
            Behaviors.same
          case UnreachableMember(member) =>
            ctx.log.info("Member detected as unreachable: {}", member)
            Behaviors.same
          case MemberRemoved(member, previousStatus) =>
            ctx.log.info("Member is Removed: {} after {}", member.address, previousStatus)
            Behaviors.same
        }

      active()
    }
}
