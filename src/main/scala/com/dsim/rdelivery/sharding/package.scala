package com.dsim
package rdelivery

package object sharding {

  /**
   *
   *
   * https://github.com/akka/akka/pull/28155
   * https://doc.akka.io/docs/akka/current/typed/reliable-delivery.html#sharding
   * https://github.com/akka/akka/blob/c63d18f663d0bf9e602cd2337b77d8feac87785b/akka-cluster-sharding-typed/src/test/scala/akka/cluster/sharding/typed/delivery/ReliableDeliveryShardingSpec.scala
   *
   */

  akka.cluster.sharding.typed.delivery.ShardingProducerController

  akka.cluster.sharding.typed.delivery.ShardingConsumerController

}
