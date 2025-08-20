package com.dsim

import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.receptionist.ServiceKey
import com.dsim.domain.v1.ReservationPB

package object rdelivery {

  val serviceKey = ServiceKey[ConsumerController.Command[ReservationPB]]("workers")
}
