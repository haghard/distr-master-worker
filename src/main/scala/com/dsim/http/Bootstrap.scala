package com
package dsim.http

import akka.Done
import akka.http.scaladsl.Http
import akka.actor.typed.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.server.Route
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseServiceRequestsDone, PhaseServiceStop, PhaseServiceUnbind, Reason}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Bootstrap {

  case object CriticalError                 extends Reason
  private case object HttpServerBindFailure extends Reason

  private val terminationDeadline = 5.seconds
}

final case class Bootstrap(routes: Route, host: String, httpPort: Int)(implicit
  system: ActorSystem[Nothing]
) {
  // implicit val b = system.toClassic
  implicit val s        = system.executionContext
  private val cShutdown = CoordinatedShutdown(system)

  Http()
    .newServerAt(host, httpPort)
    .bindFlow(routes)
    .onComplete {
      case Failure(ex) ⇒
        system.log.error(s"Shutting down because can't bind on $host:$httpPort", ex)
        cShutdown.run(Bootstrap.HttpServerBindFailure)
      case Success(binding) ⇒
        system.log.info(s"Started http server on $host:$httpPort")
        cShutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () ⇒
          Future {
            system.log.info("CoordinatedShutdown [before-unbind]")
            Done
          }
        }

        cShutdown.addTask(PhaseServiceUnbind, "http-api.unbind") { () ⇒
          // No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.unbind().map { r ⇒
            system.log.info("CoordinatedShutdown [http-api.unbind]")
            r
          }
        }

        // graceful termination request being handled on this connection
        cShutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () ⇒
          /** It doesn't accept new connection but it drains the existing connections Until the `terminationDeadline`
            * all the req that have been accepted will be completed and only than the shutdown will continue
            */
          binding.terminate(Bootstrap.terminationDeadline).map { _ ⇒
            system.log.info("CoordinatedShutdown [http-api.terminate]")
            Done
          }
        }

        // forcefully kills connections that are still open
        cShutdown.addTask(PhaseServiceStop, "close.connections") { () ⇒
          Http().shutdownAllConnectionPools().map { _ ⇒
            system.log.info("CoordinatedShutdown [close.connections]")
            Done
          }
        }

        /*cShutdown.addTask(PhaseServiceStop, "stop.smth") { () =>
          smth.stop().map { _ =>
            classicSystem.log.info("CoordinatedShutdown [stop.smth]")
            Done
          }
        }*/

        cShutdown.addTask(PhaseActorSystemTerminate, "sys.term") { () ⇒
          Future.successful {
            system.log.info("CoordinatedShutdown [sys.term]")
            Done
          }
        }
    }
}
