package com.dsim

import akka.actor.{ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.event.Logging
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import scala.concurrent.duration._
import scala.util.control.NonFatal

object CassandraSessionExtension extends ExtensionId[CassandraSessionExtension] with ExtensionIdProvider {

  override def get(system: ActorSystem): CassandraSessionExtension = super.get(system)

  override def get(system: ClassicActorSystemProvider): CassandraSessionExtension = super.get(system)

  override def lookup = CassandraSessionExtension

  override def createExtension(system: ExtendedActorSystem): CassandraSessionExtension =
    new CassandraSessionExtension(system)
}

class CassandraSessionExtension(system: ActorSystem) extends Extension {

  val retryTimeout = 3.second
  val logger       = Logging(system, getClass)

  val keyspace = system.settings.config.getString("akka.persistence.cassandra.journal.keyspace")

  lazy val session: CassandraSession = {
    implicit val ec = system.dispatcher

    val cassandraSession = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      .session

    val f = createTables(cassandraSession).recoverWith { case NonFatal(ex) ⇒
      system.log.error("DDL error", ex)
      akka.pattern.after(retryTimeout, system.scheduler)(createTables(cassandraSession))
    }

    //TODO: avoid blocking
    scala.concurrent.Await.result(f, Duration.Inf)
    cassandraSession
  }

  private def createTables(cassandraSession: CassandraSession) = {
    val ttl = system.settings.config.getDuration("akka.cluster.split-brain-resolver.stable-after").getSeconds * 3
    val createStatement =
      s"CREATE TABLE IF NOT EXISTS msg.leases (name text PRIMARY KEY, owner text) with default_time_to_live = $ttl"

    cassandraSession
      .executeDDL(createStatement)
    //.flatMap(_ ⇒ cassandraSession.executeDDL(createFeedbackStatement))
  }
}
