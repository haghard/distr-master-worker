### 


```bash

docker-compose -f docker-compose5.yml up

sbt a    
sbt b
sbt d

```



### Main links

https://www.sestevez.com/sestevez/CassandraDataModeler/
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

https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#work-pulling
https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#reliable-delivery


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






