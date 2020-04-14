package com
package sim.http

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Route, RouteResult}

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.{PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}
import akka.cluster.Cluster
import akka.management.cluster.ClusterHttpManagementRouteProvider
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.http.scaladsl.server.RouteConcatenation._

object HttpBootstrap {
  final private case object BindFailure extends Reason

  val terminationDeadline = 4.seconds
}

class HttpBootstrap(routes: Route, host: String, httpPort: Int)(
  implicit system: akka.actor.ActorSystem,
  cShutdown: CoordinatedShutdown
) {

  implicit val ex = system.dispatcher

  ClusterHttpManagementRouteProvider(system)
  val managementRoutes = ClusterHttpManagementRoutes(Cluster(system))

  val api = managementRoutes ~ routes

  system.log.warning(s"★ ★ ★ Started http server on $host:$httpPort ★ ★ ★ ")

  Http()
    .bindAndHandle(RouteResult.route2HandlerFlow(api), host, httpPort)
    .onComplete {
      case Failure(ex) =>
        system.log.error(s"Shutting down because can't bind to $host:$httpPort", ex)
        cShutdown.run(HttpBootstrap.BindFailure)
      case Success(binding) =>
        system.log.info(s"Listening for HTTP connections on ${binding.localAddress}")
        cShutdown.addTask(PhaseServiceUnbind, "api.unbind") { () =>
          system.log.info("api.unbind")
          // No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.terminate(HttpBootstrap.terminationDeadline).map(_ => Done)
        }

        //graceful termination request being handled on this connection
        cShutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () =>
          system.log.info("http-api.terminate")
          //It doesn't accept new connection but it drains the existing connections
          //Until the terminationDeadline all the req that have been accepted will be completed
          //and only that the shutdown will continue
          binding.terminate(HttpBootstrap.terminationDeadline).map(_ => Done)
        }
    }
}
