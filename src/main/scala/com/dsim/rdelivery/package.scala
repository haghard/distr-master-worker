package com.dsim

import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.receptionist.ServiceKey
import com.dsim.domain.v1.WorkerTaskPB

//https://github.com/akka/akka/pull/28155
//https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#work-pulling
package object rdelivery {

  val serviceKey = ServiceKey[ConsumerController.Command[WorkerTaskPB]]("workers")
}
