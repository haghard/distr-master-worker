### 


```bash


To start cassandra 

sbt "runMain com.dsim.Runner 2553"

    
sbt "runMain com.dsim.Runner 2551"
sbt "runMain com.dsim.Runner 2552"
sbt "runMain com.dsim.Runner 2553"


https://www.sestevez.com/sestevez/CassandraDataModeler/

describe keyspaces;

use msg;

describe msg;

drop keyspace msg;

select persistence_id, sequence_nr, timestamp, timebucket, ser_id, ser_manifest, writer_uuid from msg.msg_journal where persistence_id = 'messages' and partition_nr = 0;

select persistence_id, partition_nr, sequence_nr from msg.msg_journal where persistence_id = 'messages' and partition_nr = 8;

select persistence_id, partition_nr, sequence_nr from msg.msg_journal where persistence_id = 'messages' ALLOW FILTERING;

CREATE TABLE msg.msg_journal (
    persistence_id text,
    partition_nr bigint,
    sequence_nr bigint,
    timestamp timeuuid,
    event blob,
    event_manifest text,
    meta blob,
    meta_ser_id int,
    meta_ser_manifest text,
    ser_id int,
    ser_manifest text,
    tags set<text>,
    timebucket text,
    writer_uuid text,
    PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC)



select name, owner from leases where name = 'dsim-akka-sbr';

```


### Main links

https://github.com/akka/akka/pull/28155
https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#sharding
https://github.com/akka/akka-samples/tree/05703965e15cab5efbb66718a6e23ef42de05ff0/akka-sample-distributed-workers-scala


### Other links

https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#work-pulling

https://discuss.lightbend.com/t/how-to-avoid-nodes-to-be-quarantined-in-akka-cluster/1932
https://manuel.bernhardt.io/2017/06/08/akka-anti-patterns-using-remoting/
https://doc.akka.io/docs/akka/snapshot/remoting.html?language=scala#types-of-remote-interaction

https://doc.akka.io/docs/akka/current/coordinated-shutdown.html
https://doc.akka.io/docs/akka/current/remoting-artery.html#quarantine

https://github.com/akka/akka-samples/tree/2.6/akka-sample-distributed-workers-scala
https://github.com/akka/akka-samples/blob/2.6/akka-sample-distributed-workers-scala/src/main/scala/worker/WorkManager.scala

https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-4-the-source-code
https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#work-pulling


### Notes


Find the PID for the unreachable node:
> lsof -i :2551 | grep LISTEN | awk '{print $2}'

Suspend
> kill -stop <pid>

Resume
> kill -cont <pid>

Hard kill
> kill -9 <pid>



http 127.0.0.1:2651/cluster/members
http 127.0.0.1:2652/cluster/members


curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=leave http://localhost:2651/cluster/members/dsim@127.0.0.1:2552
curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=down http://localhost:2651/cluster/members/dsim@127.0.0.1:2552




### Akka-persistence-postgres

https://medium.com/swissborg-engineering/leveraging-aws-aurora-for-event-sourcing-e8323dce58b6
https://aws.amazon.com/rds/aurora/postgresql-features/

https://github.com/SwissBorg/akka-persistence-postgres

"com.swissborg" %% "akka-persistence-postgres" % "0.5.0-M7"


### How to run

runMain com.dsim.Runner 2554 (cassandra)
runMain com.dsim.Runner 2551
runMain com.dsim.Runner 2552

select persistence_id, sequence_nr, timestamp, timebucket, ser_id, ser_manifest, writer_uuid from msg.msg_journal where persistence_id = 'tasks' and partition_nr = 0;