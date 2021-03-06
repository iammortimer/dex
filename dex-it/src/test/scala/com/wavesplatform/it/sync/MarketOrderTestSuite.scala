package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.account.KeyPair
import com.wavesplatform.dex.domain.asset.Asset.Waves
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.order.OrderType.{BUY, SELL}
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.it.api.responses.dex.{OrderBookHistoryItem, OrderStatus, OrderStatusResponse}
import com.wavesplatform.dex.model.AcceptedOrderType
import com.wavesplatform.dex.settings.AssetType._
import com.wavesplatform.dex.settings.FeeMode._
import com.wavesplatform.it.MatcherSuiteBase

import scala.concurrent.duration.DurationInt

class MarketOrderTestSuite extends MatcherSuiteBase {

  val fixedFee: Long  = 0.04.TN
  val percentFee: Int = 14

  def tooLowPrice(orderType: String, price: String): String = {
    s"Price of the $orderType market order ($price) is too low for its full execution with the current market state"
  }

  def tooHighPrice(orderType: String, price: String): String = {
    s"Price of the $orderType market order ($price) is too high for its full execution with the current market state"
  }

  override protected def dexInitialSuiteConfig: Config = ConfigFactory.parseString(
    s"""
       |TN.dex {
       |  price-assets = [ "$UsdId", "TN", "$EthId", "$BtcId", "$WctId" ]
       |  allowed-order-versions = [1, 2, 3]
       |  order-fee.-1 {
       |    mode = $PERCENT
       |    $DYNAMIC {
       |      base-fee = 4000000
       |    }
       |    $PERCENT {
       |      asset-type = $AMOUNT
       |      min-fee = $percentFee
       |    }
       |    $FIXED {
       |      asset =  TN
       |      min-fee = 4000000
       |    }
       |  }
       |}
       """.stripMargin
  )

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueWctTx, IssueUsdTx, IssueEthTx, IssueBtcTx)
    dex1.start()
    broadcastAndAwait(mkTransfer(alice, bob, 10000.usd, usd, fixedFee))
  }

  override protected def afterEach(): Unit = {
    super.afterEach()
    dex1.api.cancelAll(alice)
    dex1.api.cancelAll(bob)
  }

  def placeOrders(sender: KeyPair, pair: AssetPair, orderType: OrderType, feeMode: FeeMode = FIXED)(orders: (Long, Long)*): Seq[Order] = {
    val now = System.currentTimeMillis
    orders.zipWithIndex.map {
      case ((amount, price), idx) =>
        val o = mkOrder(sender, pair, orderType, amount, price, version = 3: Byte, matcherFee = getFee(feeMode), ts = now + idx)
        dex1.api.place(o)
        o
    }
  }

  def placeMarketOrder(sender: KeyPair, pair: AssetPair, orderType: OrderType, amount: Long, price: Long): OrderStatusResponse = {
    val mo = mkOrder(sender, pair, orderType, amount, price, matcherFee = fixedFee)
    dex1.api.placeMarket(mo)
    dex1.api.waitForOrderStatus(mo, OrderStatus.Filled)
  }

  def getFee(feeMode: FeeMode): Long = feeMode match {
    case PERCENT => percentFee.TN
    case FIXED   => fixedFee
    case _       => 0L
  }

  def calculateFeeValue(amount: Long, feeMode: FeeMode): Long = feeMode match {
    case PERCENT => amount / 100 * percentFee
    case FIXED   => fixedFee
  }

  "Processing market orders" - {

    def testFilledMarketOrder(orderType: OrderType, feeMode: FeeMode): Unit = {
      val amount = 100.TN
      val price  = 1.usd
      val fee    = getFee(feeMode)

      var account1: KeyPair = null
      var account2: KeyPair = null

      if (orderType == SELL) {
        account1 = createAccountWithBalance(200.usd         -> usd, fee -> Waves)
        account2 = createAccountWithBalance(200.TN + fee -> Waves)
        placeOrders(account1, wavesUsdPair, BUY, feeMode)(amount -> price)
      } else {
        account1 = createAccountWithBalance(200.TN + fee -> Waves)
        account2 = createAccountWithBalance(200.usd         -> usd, fee -> Waves)
        placeOrders(account1, wavesUsdPair, SELL, feeMode)(amount -> price)
      }

      val marketOrder = mkOrder(account2, wavesUsdPair, orderType, amount, price, fee)

      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled).filledAmount shouldBe Some(amount)
      waitForOrderAtNode(marketOrder)

      eventually { wavesNode1.api.balance(account1, Waves) shouldBe amount }
      wavesNode1.api.balance(account1, usd) should be(200.usd - price * amount / 1.TN)

      wavesNode1.api.balance(account2, Waves) shouldBe amount
      wavesNode1.api.balance(account2, usd) should be(price * amount / 1.TN)

      def validateHistory(label: String, orders: Seq[OrderBookHistoryItem]): Unit = withClue(s"$label: ") {
        orders should have size 1
        orders.head.orderType shouldBe AcceptedOrderType.Market
      }

      validateHistory("by pair", dex1.api.orderHistoryByPair(account2, wavesUsdPair))
      validateHistory("full", dex1.api.orderHistory(account2))
      validateHistory("admin", dex1.api.orderHistoryWithApiKey(account2, activeOnly = Some(false)))

      Seq(account1, account2).foreach { dex1.api.cancelAll(_) }
    }

    "percent fee mode" - {

      "processing market order (SELL)" in {
        testFilledMarketOrder(SELL, PERCENT)
      }

      "processing market order (BUY)" in {
        testFilledMarketOrder(BUY, PERCENT)
      }
    }

    "fixed fee mode" - {

      "processing market order (SELL)" in {
        dex1.restartWithNewSuiteConfig(ConfigFactory.parseString(s"TN.dex.order-fee.-1.mode = $FIXED").withFallback(dexInitialSuiteConfig))

        testFilledMarketOrder(SELL, FIXED)
      }

      "processing market order (BUY)" in {
        testFilledMarketOrder(BUY, FIXED)
      }
    }

    "should be matched with order having the best price (BUY)" in {
      val marketPrice = 0.4.usd
      val amount      = 25.TN
      val bestPrice   = 0.3.usd

      placeOrders(alice, wavesUsdPair, SELL)(
        50.TN -> 0.33.usd,
        10.TN -> bestPrice,
        25.TN -> 0.35.usd,
      )

      val marketOrder = mkOrder(bob, wavesUsdPair, BUY, amount, marketPrice, fixedFee)
      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled)

      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.bids should be(empty)
      orderBook.asks shouldNot be(empty)

      orderBook.asks.filter(_.price == bestPrice) should be(empty)
      orderBook.asks should have size 2

      waitForOrderAtNode(marketOrder)
    }

    "should be matched with order having the best price (SELL)" in {
      val marketPrice = 0.6.usd
      val amount      = 25.TN
      val bestPrice   = 0.8.usd
      val secondPrice = 0.79.usd

      placeOrders(alice, wavesUsdPair, BUY)(
        10.TN -> bestPrice,
        50.TN -> 0.78.usd,
        25.TN -> secondPrice,
      )

      val marketOrder = mkOrder(bob, wavesUsdPair, SELL, amount, marketPrice, fixedFee)
      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled)

      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.bids shouldNot be(empty)
      orderBook.asks should be(empty)

      orderBook.asks.filter(_.price == bestPrice) should be(empty)
      orderBook.bids.filter(order => order.price == secondPrice && order.amount == 10.TN) should have size 1
      orderBook.bids should have size 2

      waitForOrderAtNode(marketOrder)
    }

    "should be removed from order book when the restriction by tokens count has been reached (BUY)" in {
      val bobWBefore        = wavesNode1.api.balance(bob, Waves)
      val bobUBefore        = wavesNode1.api.balance(bob, usd)
      val marketPrice       = 0.5.usd
      val marketOrderAmount = 150.TN
      val ordersAmount      = 120.TN

      placeOrders(alice, wavesUsdPair, SELL)(
        30.TN -> 0.3.usd,
        40.TN -> 0.4.usd,
        50.TN -> 0.5.usd,
      )

      val marketOrder = mkOrder(bob, wavesUsdPair, BUY, marketOrderAmount, marketPrice, fixedFee)
      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled).filledAmount shouldBe Some(ordersAmount)

      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.bids should be(empty)
      orderBook.asks should be(empty)

      /* market order fee value depends on matched orders in proportion */
      waitForOrderAtNode(marketOrder)
      eventually {
        wavesNode1.api.balance(bob, Waves) should be(bobWBefore + ordersAmount - fixedFee * ordersAmount / marketOrderAmount)
      }

      wavesNode1.api.balance(bob, usd) should be(
        bobUBefore - 0.3.usd * 30.TN / 1.TN - 0.4.usd * 40.TN / 1.TN - 0.5.usd * 50.TN / 1.TN)
    }

    "should be removed from order book when the restriction by tokens count has been reached (SELL)" in {
      val marketPrice       = 0.1.usd
      val marketOrderAmount = 72.TN
      val ordersAmount      = 36.TN

      val buyer  = createAccountWithBalance(100.usd      -> usd, 3 * fixedFee -> Waves)
      val seller = createAccountWithBalance(ordersAmount -> Waves, fixedFee   -> Waves)

      placeOrders(buyer, wavesUsdPair, BUY)(
        12.TN -> 0.2.usd,
        12.TN -> 0.3.usd,
        12.TN -> 0.4.usd,
      )

      val marketOrder = mkOrder(seller, wavesUsdPair, SELL, marketOrderAmount, marketPrice, fixedFee)

      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled).filledAmount shouldBe Some(ordersAmount)

      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.bids should be(empty)
      orderBook.asks should be(empty)

      waitForOrderAtNode(marketOrder)
      eventually {
        wavesNode1.api.balance(seller, Waves) should be(fixedFee / (marketOrderAmount / ordersAmount))
      }

      wavesNode1.api.balance(seller, usd) should be(0.2.usd * 12.TN / 1.TN + 0.3.usd * 12.TN / 1.TN + 0.4.usd * 12.TN / 1.TN)
    }

    "should be removed from order book when there are no suitable orders by limit of the price (BUY)" in {
      val accountBalanceWBefore = 1.TN
      val marketOrderAmount     = 200.TN
      val marketPrice           = 0.1.usd
      val anotherOrderAmount    = 1.TN

      val account = createAccountWithBalance(
        accountBalanceWBefore -> Waves,
        100.usd               -> usd
      )

      val creationTime = System.currentTimeMillis

      dex1.api.place(mkOrder(alice, wavesUsdPair, SELL, 200.TN, marketPrice, fixedFee))
      dex1.api.place(mkOrder(bob, wavesUsdPair, BUY, 1.TN, marketPrice, fixedFee * 2, ts = creationTime))

      val marketOrder = mkOrder(account, wavesUsdPair, BUY, marketOrderAmount, marketPrice, fixedFee, ts = creationTime)
      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled)

      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.bids should be(empty)
      orderBook.asks should be(empty)

      waitForOrderAtNode(marketOrder)
      eventually {
        wavesNode1.api.balance(account, Waves) should be(
          accountBalanceWBefore + marketOrderAmount - anotherOrderAmount - fixedFee * (marketOrderAmount - anotherOrderAmount) / marketOrderAmount)
      }
    }

    "should be accepted if price * amount > current balance, but it can be filled by offers with lower price" in {
      val marketOrderAmount = 150.TN
      val marketOrderPrice  = 1.usd
      val accountUsdBalance = 100.usd
      val account           = createAccountWithBalance(accountUsdBalance -> usd, 0.003.TN -> Waves)

      placeOrders(alice, wavesUsdPair, SELL)(
        5.TN   -> 0.2.usd,
        15.TN  -> 0.3.usd,
        30.TN  -> 0.4.usd,
        100.TN -> 0.5.usd,
        150.TN -> 2.usd,
      )

      val marketOrder = mkOrder(account, wavesUsdPair, BUY, marketOrderAmount, marketOrderPrice, fixedFee)
      dex1.api.placeMarket(marketOrder)
      dex1.api.waitForOrderStatus(marketOrder, OrderStatus.Filled).filledAmount shouldBe Some(marketOrderAmount)

      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.asks should have size 1
      orderBook.bids should be(empty)

      waitForOrderAtNode(marketOrder)
      eventually {
        wavesNode1.api.balance(account, Waves) shouldBe marketOrderAmount
      }
      wavesNode1.api.balance(account, usd) should be(accountUsdBalance - 5 * 0.2.usd - 15 * 0.3.usd - 30 * 0.4.usd - 100 * 0.5.usd)

      dex1.api.cancelAll(account)
    }
  }

  "Validation market orders" - {
    val price = 0.4.usd

    "should be accepted if there is no way to fill it completely (sum of all orders in order book < amount of market order" in {
      placeOrders(bob, wavesUsdPair, SELL)(100.TN -> price)

      val total       = dex1.api.orderBook(wavesUsdPair).bids.map(_.amount).sum
      val marketOrder = mkOrder(alice, wavesUsdPair, BUY, total + 1000.TN, price, fixedFee)

      dex1.api.placeMarket(marketOrder)
      waitForOrderAtNode(marketOrder)
    }

    "should be rejected if the price is too low for completely filling by current opened orders (BUY)" in {
      placeOrders(alice, wavesUsdPair, SELL)(
        1.TN -> 1.usd,
        1.TN -> 2.usd,
        1.TN -> 3.usd,
      )

      dex1.api.tryPlaceMarket(mkOrder(bob, wavesUsdPair, BUY, 3.TN, 2.usd, fixedFee)) should failWith(
        19927055,
        tooLowPrice("buy", "2")
      )
    }

    "should be rejected if the price is too high for completely filling by current opened orders (SELL)" in {
      val amount      = 100.TN
      val marketPrice = price + 0.1.usd

      placeOrders(bob, wavesUsdPair, BUY)(amount -> price)

      dex1.api.tryPlaceMarket(mkOrder(alice, wavesUsdPair, SELL, amount, marketPrice, fixedFee)) should failWith(
        19927055,
        tooHighPrice("sell", "0.5")
      )
    }

    "should be rejected if amount of the buy market order more then user could buy" in {
      val amount   = 101.TN
      val price    = 1.1.usd
      val transfer = 100.usd

      val account = createAccountWithBalance(transfer -> usd)

      placeOrders(alice, wavesUsdPair, SELL)(amount -> price)
      dex1.api.tryPlaceMarket(mkOrder(account, wavesUsdPair, BUY, amount, price, fixedFee)) should failWith(
        3147270, // BalanceNotEnough
        s"0.01 ${UsdId.toString} and 0.003 WAVES"
      )
    }

    "should be rejected if user has enough balance to fill market order, but has not enough balance to pay fee in another asset" in {
      dex1.restartWithNewSuiteConfig(
        ConfigFactory
          .parseString(s"TN.dex.order-fee.-1.fixed.asset = $BtcId\nTN.dex.order-fee.-1.mode = $FIXED")
          .withFallback(dexInitialSuiteConfig)
      )

      val amount   = 10.TN
      val price    = 1.usd
      val transfer = 10.usd

      val account = createAccountWithBalance { transfer -> usd }

      placeAndAwaitAtDex { mkOrder(bob, wavesUsdPair, SELL, amount, price, fixedFee, feeAsset = btc, version = 3) }

      dex1.api.tryPlaceMarket(mkOrder(account, wavesUsdPair, BUY, amount, price, fixedFee, feeAsset = btc)) should failWith(
        3147270,
        s"0.003 ${BtcId.toString}"
      )
    }
  }

  "Market order creation is possible when spenadable balance is equal to reservable" in {

    dex1.restartWithNewSuiteConfig(ConfigFactory.parseString(s"TN.dex.order-fee.-1.mode = $DYNAMIC").withFallback(dexInitialSuiteConfig))

    val carol = KeyPair("carol".getBytes)

    broadcastAndAwait(mkTransfer(alice, carol, 10.TN, Waves))

    val order1 = mkOrderDP(carol, wavesUsdPair, SELL, 9.997.TN, 3.0, ttl = 1.day)
    val order2 = mkOrderDP(carol, wavesUsdPair, SELL, 9.997.TN, 3.0, ttl = 2.days)

    dex1.api.place(order1)
    dex1.api.reservedBalance(carol) should matchTo(Map[Asset, Long](Waves -> 10.TN))
    wavesNode1.api.balance(carol, Waves) shouldBe 10.TN

    dex1.api.tryPlaceMarket(order2) should failWith(
      3147270,
      "Not enough tradable balance"
    )
  }

  "Market order should be executed even if sender balance isn't enough to cover order value" in {

    dex1.restartWithNewSuiteConfig(ConfigFactory.parseString(s"waves.dex.order-fee.-1.mode = $DYNAMIC") withFallback dexInitialSuiteConfig)

    val carol = createAccountWithBalance(300.usd -> usd, 5.TN -> Waves)

    placeAndAwaitAtDex(mkOrderDP(alice, wavesUsdPair, SELL, 1000.TN, 0.5))
    placeAndAwaitAtNode(mkOrderDP(carol, wavesUsdPair, BUY, 1000L.TN, 0.6), isMarketOrder = true)

    wavesNode1.api.balance(carol, Waves) shouldBe 604.9982.TN
    wavesNode1.api.balance(carol, usd) shouldBe 0
  }
}
