package com
package dsim.http

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseServiceRequestsDone, PhaseServiceStop, PhaseServiceUnbind, Reason}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ServerBootstrap {

  private case object HttpServerBindFailure extends Reason

  private val terminationDeadline = 5.seconds
}

case class ServerBootstrap(
  routes: Route,
  host: String,
  httpPort: Int,
  terminationDeadline: FiniteDuration //http layer ask timeout + some extra time
)(implicit
  classicSystem: akka.actor.ActorSystem
) {
  implicit val ex       = classicSystem.dispatcher
  private val cShutdown = CoordinatedShutdown(classicSystem)

  Http()
    .bindAndHandle(routes, host, httpPort)
    .onComplete {
      case Failure(ex) =>
        classicSystem.log.error(s"Shutting down because can't bind on $host:$httpPort", ex)
        cShutdown.run(ServerBootstrap.HttpServerBindFailure)
      case Success(binding) =>
        classicSystem.log.info(s"Started http server on $host:$httpPort")
        cShutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
          Future {
            classicSystem.log.info("CoordinatedShutdown [before-unbind]")
            Done
          }
        }

        cShutdown.addTask(PhaseServiceUnbind, "http-api.unbind") { () =>
          //No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.unbind().map { r =>
            classicSystem.log.info("CoordinatedShutdown [http-api.unbind]")
            r
          }
        }

        //graceful termination request being handled on this connection
        cShutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () =>
          /**
            * It doesn't accept new connection but it drains the existing connections
            * Until the `terminationDeadline` all the req that have been accepted will be completed
            * and only than the shutdown will continue
            */
          binding.terminate(ServerBootstrap.terminationDeadline).map { _ =>
            classicSystem.log.info("CoordinatedShutdown [http-api.terminate]")
            Done
          }
        }

        //forcefully kills connections that are still open
        cShutdown.addTask(PhaseServiceStop, "close.connections") { () =>
          Http().shutdownAllConnectionPools().map { _ =>
            classicSystem.log.info("CoordinatedShutdown [close.connections]")
            Done
          }
        }

        /*cShutdown.addTask(PhaseServiceStop, "stop.smth") { () =>
          smth.stop().map { _ =>
            classicSystem.log.info("CoordinatedShutdown [stop.smth]")
            Done
          }
        }*/

        cShutdown.addTask(PhaseActorSystemTerminate, "sys.term") { () =>
          Future.successful {
            classicSystem.log.info("CoordinatedShutdown [sys.term]")
            Done
          }
        }
    }
}
