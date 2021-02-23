package com.dsim

import akka.actor.typed.Behavior
import akka.cluster.ClusterEvent._
import akka.cluster.typed.Subscribe
import akka.actor.typed.scaladsl.Behaviors

object ClusterListenerActor {

  def apply(cluster: akka.cluster.typed.Cluster): Behavior[ClusterDomainEvent] =
    Behaviors.setup[ClusterDomainEvent] { ctx ⇒
      cluster.subscriptions.tell(Subscribe(ctx.self.ref, classOf[ClusterDomainEvent]))

      def active(): Behavior[ClusterDomainEvent] =
        Behaviors.receiveMessagePartial {
          case MemberUp(member) ⇒
            ctx.log.info("Member is Up: {}", member.address)
            Behaviors.same
          case UnreachableMember(member) ⇒
            ctx.log.info("Member detected as unreachable: {}", member)
            Behaviors.same
          case MemberRemoved(member, previousStatus) ⇒
            ctx.log.info("Member is Removed: {} after {}", member.address, previousStatus)
            Behaviors.same
        }

      active()
    }
}
