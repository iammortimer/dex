package com.wavesplatform.dex.grpc.integration.caches

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

import mouse.any.anySyntaxMouse
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}

class BlockchainCacheSpecification extends WordSpecLike with Matchers {

  private class BlockchainCacheTest(loader: String => Future[String], expiration: Option[Duration], invalidationPredicate: String => Boolean)
      extends BlockchainCache[String, String](loader, expiration, invalidationPredicate)

  private def createCache(loader: String => Future[String],
                          expiration: Option[Duration] = None,
                          invalidationPredicate: String => Boolean = _ => false): BlockchainCacheTest = {
    new BlockchainCacheTest(loader, expiration, invalidationPredicate)
  }

  private val andThenAwaitTimeout = 300

  "BlockchainCache" should {

    "not keep failed futures" in {

      val goodKey = "good key"
      val badKey  = "gRPC Error"

      val keyAccessMap = new ConcurrentHashMap[String, Int] unsafeTap (m => { m.put(goodKey, 0); m.put(badKey, 0) })
      val gRPCError    = new RuntimeException("gRPC Error occurred")

      val cache =
        createCache(
          key => {
            (if (key == badKey) Future.failed(gRPCError) else Future.successful(s"value = $key")) unsafeTap { _ =>
              keyAccessMap.computeIfPresent(key, (_, prev) => prev + 1)
            }
          }
        )

      val badKeyAccessCount = 10

      Await.result(
        (1 to badKeyAccessCount).foldLeft { Future.successful("") } { (prev, _) =>
          for {
            _ <- prev
            _ <- cache get goodKey
            r <- cache get badKey recover { case _ => "sad" }
          } yield { Thread.sleep(andThenAwaitTimeout); r }
        },
        scala.concurrent.duration.Duration.Inf
      )

      keyAccessMap.get(goodKey) shouldBe 1
      keyAccessMap.get(badKey) shouldBe badKeyAccessCount
    }

    "not keep values according to the predicate" in {

      val goodKey = "111"
      val badKey  = "222"

      val keyAccessMap = new ConcurrentHashMap[String, Int] unsafeTap (m => { m.put(goodKey, 0); m.put(badKey, 0) })

      val cache = createCache(
        key => { Future.successful(key) unsafeTap (_ => keyAccessMap.computeIfPresent(key, (_, prev) => prev + 1)) },
        invalidationPredicate = result => result startsWith "2"
      )

      val badKeyAccessCount = 10

      Await.result(
        (1 to badKeyAccessCount).foldLeft { Future.successful("") } { (prev, _) =>
          for {
            _ <- prev
            _ <- cache get goodKey
            r <- cache get badKey
          } yield { Thread.sleep(andThenAwaitTimeout); r }
        },
        scala.concurrent.duration.Duration.Inf
      )

      keyAccessMap.get(goodKey) shouldBe 1
      keyAccessMap.get(badKey) shouldBe badKeyAccessCount
    }
  }
}