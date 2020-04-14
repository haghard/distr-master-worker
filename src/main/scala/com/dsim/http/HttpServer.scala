package com
package dsim.http

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Route, RouteResult}

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}
import akka.cluster.Cluster
import akka.management.cluster.ClusterHttpManagementRouteProvider
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.http.scaladsl.server.RouteConcatenation._

import scala.concurrent.Future

object HttpServer {
  final private case object BindFailure extends Reason

  val terminationDeadline = 4.seconds
}

case class HttpServer(routes: Route, host: String, httpPort: Int)(
  implicit system: akka.actor.ActorSystem
) {

  implicit val ex = system.dispatcher
  val cShutdown   = CoordinatedShutdown(system)

  ClusterHttpManagementRouteProvider(system)
  val managementRoutes = ClusterHttpManagementRoutes(Cluster(system))

  val api = managementRoutes ~ routes

  Http()
    .bindAndHandle(RouteResult.route2HandlerFlow(api), host, httpPort)
    .onComplete {
      case Failure(ex) =>
        system.log.error(s"Shutting down because can't bind to $host:$httpPort", ex)
        cShutdown.run(HttpServer.BindFailure)
      case Success(binding) =>
        system.log.warning(s"★ ★ ★ Started http server on ${binding.localAddress} ★ ★ ★ ")
        cShutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
          Future.successful {
            system.log.warning("★ ★ ★ CoordinatedShutdown starts [before-service-unbind]  ★ ★ ★")
            Done
          }
        }

        cShutdown.addTask(PhaseServiceUnbind, "api.unbind") { () =>
          system.log.info("api.unbind")
          // No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.terminate(HttpServer.terminationDeadline).map(_ => Done)
        }

        //graceful termination request being handled on this connection
        cShutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () =>
          system.log.info("http-api.terminate")
          //It doesn't accept new connection but it drains the existing connections
          //Until the terminationDeadline all the req that have been accepted will be completed
          //and only than the shutdown will continue
          binding.terminate(HttpServer.terminationDeadline).map(_ => Done)
        }

        cShutdown.addTask(PhaseActorSystemTerminate, "sys.term") { () =>
          Future.successful {
            system.log.warning("★ ★ ★ CoordinatedShutdown reaches the last phase [actor-system-terminate] ★ ★ ★")
            Done
          }
        }

    }
}
