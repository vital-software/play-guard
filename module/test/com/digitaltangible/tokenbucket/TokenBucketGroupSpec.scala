package com.digitaltangible.tokenbucket

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.digitaltangible.FakeClock
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, MustMatchers}
import scala.concurrent.ExecutionContext.Implicits.global

class TokenBucketGroupSpec extends TestKit(ActorSystem("TokenBucketGroupTest")) with FlatSpecLike with MustMatchers with ScalaFutures with GeneratorDrivenPropertyChecks {

  "TokenBucketGroup.create" should
    "allow only values for size and rate in their range" in {
    forAll { (size: Int, rate: Float) =>
      if ((1 to 1000 contains size) && (rate <= 1 && rate >= 0.000001f)) {
        TokenBucketGroup.create(size, rate)
      } else {
        intercept[IllegalArgumentException] {
          TokenBucketGroup.create(size, rate)
        }
      }
      ()
    }
    TokenBucketGroup.create(1, 0.000001f)
    intercept[IllegalArgumentException] {
      TokenBucketGroup.create(1, 0.0000001f)
    }
  }

  "TokenBucketGroup.consume" should
    "allow 'token count' <= 'bucket size' at the same moment" in {
    val fakeClock = new FakeClock
    forAll(Gen.choose(0, 1000)) { (i: Int) =>
      val ref = TokenBucketGroup.create(1000, 2, fakeClock)
      TokenBucketGroup.consume(ref, "x", i).futureValue mustBe 1000 - i
    }
  }

  it should "not allow 'token count' > 'bucket size' at the same moment" in {
    val fakeClock = new FakeClock
    forAll(Gen.posNum[Int]) { (i: Int) =>
      val ref = TokenBucketGroup.create(1000, 2, fakeClock)
      TokenBucketGroup.consume(ref, "x", i + 1000).futureValue mustBe -i
    }
  }

  it should "not allow negative token count" in {
    val ref = TokenBucketGroup.create(100, 2)
    forAll(Gen.negNum[Int]) { (i: Int) =>
      intercept[IllegalArgumentException] {
        TokenBucketGroup.consume(ref, "x", i)
      }
    }
  }

  it should "handle different keys separately" in {
    val fakeClock = new FakeClock
    val ref = TokenBucketGroup.create(1000, 2, fakeClock)
    TokenBucketGroup.consume(ref, "asdf", 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "qwer", 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, 1, 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, 2, 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, fakeClock, 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, 2, 1).futureValue mustBe -1
    TokenBucketGroup.consume(ref, "asdf", 1).futureValue mustBe -1
    TokenBucketGroup.consume(ref, fakeClock, 1).futureValue mustBe -1
  }

  it should "regain tokens at specified rate" in {
    val fakeClock = new FakeClock
    val ref = TokenBucketGroup.create(100, 10, fakeClock)
    TokenBucketGroup.consume(ref, "x", 100).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 50
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 51
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 350
    TokenBucketGroup.consume(ref, "x", 2).futureValue mustBe 1
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 650
    TokenBucketGroup.consume(ref, "x", 3).futureValue mustBe 4
    TokenBucketGroup.consume(ref, "x", 4).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1
  }

  it should "regain tokens at specified rate < 1" in {
    val fakeClock = new FakeClock
    val ref = TokenBucketGroup.create(10, 0.1f, fakeClock)
    TokenBucketGroup.consume(ref, "x", 10).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 0

    fakeClock.ts += 9999
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 0

    fakeClock.ts += 2
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 1
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 30000
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 3
    TokenBucketGroup.consume(ref, "x", 2).futureValue mustBe 1
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 70000
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 7
    TokenBucketGroup.consume(ref, "x", 3).futureValue mustBe 4
    TokenBucketGroup.consume(ref, "x", 4).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1
  }

  it should "not overflow" in {
    val fakeClock = new FakeClock
    val ref = TokenBucketGroup.create(1000, 100, fakeClock)
    TokenBucketGroup.consume(ref, "x", 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 100000
    TokenBucketGroup.consume(ref, "x", 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1

    fakeClock.ts += 1000000
    TokenBucketGroup.consume(ref, "x", 1000).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1
  }

  it should "not underflow" in {
    val fakeClock = new FakeClock
    val ref = TokenBucketGroup.create(100, 10, fakeClock)
    TokenBucketGroup.consume(ref, "x", 100).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 100).futureValue mustBe -100
    TokenBucketGroup.consume(ref, "x", 0).futureValue mustBe 0

    fakeClock.ts += 101
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe 0
    TokenBucketGroup.consume(ref, "x", 1).futureValue mustBe -1
  }


  /**
   * NOTE: this realtime test might fail on slow machines
   */
  it should "regain tokens at specified rate with real clock" in {
    val ref = TokenBucketGroup.create(200, 1000)
    TokenBucketGroup.consume(ref, "x", 200).futureValue must be >= 0
    TokenBucketGroup.consume(ref, "x", 200).futureValue must be < 0

    Thread.sleep(100)

    TokenBucketGroup.consume(ref, "x", 100).futureValue must be >= 0
    TokenBucketGroup.consume(ref, "x", 100).futureValue must be < 0

    Thread.sleep(200)

    TokenBucketGroup.consume(ref, "x", 200).futureValue must be >= 0
    TokenBucketGroup.consume(ref, "x", 200).futureValue must be < 0

    Thread.sleep(300)

    TokenBucketGroup.consume(ref, "x", 200).futureValue must be >= 0
    TokenBucketGroup.consume(ref, "x", 200).futureValue must be < 0
  }
}