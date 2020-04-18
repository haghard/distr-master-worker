### To run locally from sbt

```bash
    
runMain com.sim.Runner 2551
runMain com.sim.Runner 2552
runMain com.sim.Runner 2553



http 127.0.0.1:2651/status
http 127.0.0.1:2652/status


http 127.0.0.1:2651/cluster/members
http 127.0.0.1:2652/cluster/members


```


### Notes

Find the PID for the unreachable node:
> lsof -i :2551 | grep LISTEN | awk '{print $2}'

Hard kill
> kill -9 <pid>

Suspend
> kill -stop <pid>

Resume
> kill -cont <pid>


curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=down http://localhost:2651/cluster/members/sim@127.0.0.1:2552


Steps to reproduce 
1. kill -stop 21342
2. curl -w '\n' -X PUT -H 'Content-Type: multipart/form-data' -F operation=down http://localhost:2651/cluster/members/sim@127.0.0.1:2552
3. kill -cont 21342

a.r.a.c.InboundActorRefCompression - Inbound message from originUid [-6086534924564321052] is using unknown compression table version. 
It may have been sent with compression table built for previous incarnation of this system. Versions activeTable: 0, nextTable: 1, incomingTable: 6

https://discuss.lightbend.com/t/how-to-avoid-nodes-to-be-quarantined-in-akka-cluster/1932


Right after singleton migration happened
14:49:28.280UTC |WARN | [sim-akka.actor.internal-dispatcher-16, sim, Association(akka://sim)] akka.remote.artery.Association - 
Association to [akka://sim@127.0.0.1:2551] with UID [840168982636099904] is irrecoverably failed. 
UID is now quarantined and all messages to this UID will be delivered to dead letters. 
Remote ActorSystem must be restarted to recover from this situation. Reason: Cluster member removed, previous status [Down]


This 
16:33:14.285UTC |INFO | [dsim-akka.remote.default-remote-dispatcher-14, dsim, Association(akka://dsim)] akka.remote.artery.Association - Association to [akka://dsim@127.0.0.1:2552] having UID [-4559786446003828560] has been stopped. All messages to this UID will be delivered to dead letters. Reason: ActorSystem terminated
indicates wrong shutdown 
and as a result Node [akka://dsim@127.0.0.1:2551] - Marking node as UNREACHABLE [Member(address = akka://dsim@127.0.0.1:2552

This indicates kill -9 
17:05:16.824UTC |WARN | [dsim-akka.actor.internal-dispatcher-4, dsim, Association(akka://dsim)] akka.remote.artery.Association - Association to [akka://dsim@127.0.0.1:2551] with UID [-8127006961833515351] is irrecoverably failed. UID is now quarantined and all messages to this UID will be delivered to dead letters. Remote ActorSystem must be restarted to recover from this situation. Reason: Cluster member removed, previous status [Down]
or split brain action 


https://doc.akka.io/docs/akka/current/coordinated-shutdown.html
https://doc.akka.io/docs/akka/current/remoting-artery.html#quarantine

https://github.com/akka/akka-samples/tree/2.6/akka-sample-distributed-workers-scala
https://github.com/akka/akka-samples/blob/2.6/akka-sample-distributed-workers-scala/src/main/scala/worker/WorkManager.scala