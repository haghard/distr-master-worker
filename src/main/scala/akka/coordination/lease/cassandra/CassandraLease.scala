package akka.coordination.lease.cassandra

import akka.actor.ExtendedActorSystem
import akka.coordination.lease.LeaseSettings
import akka.coordination.lease.scaladsl.Lease
import akka.util.ConstantFun
import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.servererrors.{WriteTimeoutException, WriteType}
import com.dsim.CassandraSessionExtension

import java.util.concurrent.atomic.AtomicBoolean
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

object CassandraLease {
  val configPath = "akka.coordination.lease.cassandra"
}

//select name, owner from leases where name = 'dsim-akka-sbr';
//see KubernetesLease

/** A lease can be used for:
  *
  * Split Brain Resolver. An additional safety measure so that only one SBR instance can make the decision to remain up.
  * Cluster Singleton. A singleton manager can be configured to acquire a lease before creating the singleton.
  * Cluster Sharding. Each Shard can be configured to acquire a lease before creating entity actors.
  * In all cases the use of the lease increases the consistency of the feature. However, as the Kubernetes API server and its backing etcd cluster can also be subject to failure and network issues any use of this lease can reduce availability.
  *
  * Lease Instances
  *  With Split Brain Resolver there will be one lease per Akka Cluster
  *  With multiple Akka Clusters using SBRs in the same namespace, e.g. multiple Lagom applications, you must ensure different ActorSystem names because they all need a separate lease.
  *  With Cluster Sharding and Cluster Singleton there will be more leases
  *  For Cluster Singleton there will be one per singleton.
  *  For Cluster Sharding, there will be one per shard per type.
  */
final class CassandraLease(system: ExtendedActorSystem, leaseTaken: AtomicBoolean, settings: LeaseSettings)
    extends Lease(settings) {

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)

  system.log.warning(s"★ ★ ★ ★ CassandraLease: $settings ★ ★ ★ ★")

  //dsim-akka-sbr
  //implicit val timeout = Timeout(settings.timeoutSettings.operationTimeout)
  val to = 3.seconds

  val cassandraSession = CassandraSessionExtension(system.classicSystem).session

  implicit val ec = cassandraSession.ec
  val cqlSession  = cassandraSession.underlying()

  //https://github.com/haghard/linguistic/blob/1b6bc8af7674982537cf574d3929cea203a2b6fa/server/src/main/scala/linguistic/dao/Accounts.scala
  //https://github.com/dekses/cassandra-lock/blob/master/src/main/java/com/dekses/cassandra/lock/LockFactory.java

  val select = SimpleStatement
    .builder("SELECT owner FROM msg.leases WHERE name = ?")
    .addPositionalValues(settings.leaseName)
    .setConsistencyLevel(ConsistencyLevel.SERIAL)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .setTracing()
    .build()

  val insert = SimpleStatement
    .builder("INSERT INTO msg.leases (name, owner) VALUES (?,?) IF NOT EXISTS")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .setConsistencyLevel(ConsistencyLevel.QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .setTracing()
    .build()

  val delete = SimpleStatement
    .builder("DELETE FROM msg.leases WHERE name = ? IF owner = ?")
    .addPositionalValues(settings.leaseName, settings.ownerName)
    .setConsistencyLevel(ConsistencyLevel.QUORUM)
    .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)
    .setTracing()
    .build()

  override def checkLease(): Boolean = false

  override def release(): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:Release {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        cqlSession.executeAsync(delete).toScala.map(_ ⇒ true) //ignore the result because we have ttl on the table
      }

  override def acquire(): Future[Boolean] =
    acquire(ConstantFun.scalaAnyToUnit)

  override def acquire(leaseLostCallback: Option[Throwable] ⇒ Unit): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:Acquire {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        //Can in fail but still acquired the lease ???
        cqlSession.executeAsync(insert).toScala.map(_.wasApplied())
      }
      .recoverWith {
        case e: WriteTimeoutException ⇒
          system.log.error(e, "Cassandra write error :")
          if (e.getWriteType eq WriteType.CAS) {
            //The timeout has happened while doing the compare-and-swap for an conditional update.
            //In this case, the update may or may not have been applied so we try to re-read it.
            cqlSession.flatMap(_.executeAsync(select).toScala.map(_.one().getString("owner") == settings.ownerName))
            //akka.pattern.after(1.second, system.scheduler)(cqlSession.flatMap(_.executeAsync(select).toScala.map(_.one().getString("owner") == settings.ownerName)))
          } else Future.successful(false)
        case NonFatal(ex) ⇒
          system.log.error(ex, "CassandraLease. Acquire error")
          Future.successful(false)
      }

  /*override def acquire(leaseLostCallback: Option[Throwable] ⇒ Unit): Future[Boolean] =
    cqlSession
      .flatMap { cqlSession ⇒
        system.log.warning("★ ★ ★ ★ CassandraLease:acquire {} by {} ★ ★ ★ ★", settings.leaseName, settings.ownerName)
        cqlSession.executeAsync(insert).toScala.flatMap { rs ⇒
          if (rs.wasApplied()) Future.successful(true)
          else akka.pattern.after(to, system.scheduler)(acquire(leaseLostCallback))
        }
      }
      .recoverWith { case NonFatal(_) ⇒
        akka.pattern.after(to, system.scheduler)(acquire(leaseLostCallback))
      }*/
}
