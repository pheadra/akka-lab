package com.comsysto.trading.akka

import akka.actor.{Actor, ActorLogging}
import com.comsysto.trading.domain._
import com.comsysto.trading.algorithm.{MarketPriceCalculator, TradeMatcher}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object OrderBook {
  case object Trade

  case class BidResponse(bid : Bid, volume : Long, price : BigDecimal)
  case class AskResponse(ask : Ask, volume : Long, price : BigDecimal)

  case object ListPrice
  case class ListPriceResponse(currentPrice : BigDecimal)
}

class OrderBook(val security: Security, var currentPrice: BigDecimal = 0) extends Actor with ActorLogging {

  this: TradeMatcher with MarketPriceCalculator =>

  import com.comsysto.trading.akka.OrderBook._

  var asks: List[Ask] = Nil
  var bids: List[Bid] = Nil


  override def preStart() = {
    context.system.scheduler.schedule(1.seconds, 1.seconds, self, Trade)
  }

  override def receive = {
    case ask@Ask(_, s, _, _) if s == security => {
      asks = ask :: asks
      val duration = (System.nanoTime() - ask.requested).nanos.toMicros
      log.info(s"Added Ask into OrderBook[${security.name}] after $duration")
    }
    case bid@Bid(_, s, _, _) if s == security => {
      bids = bid :: bids
      val duration = (System.nanoTime() - bid.requested).nanos.toMicros
      log.info(s"Added Bid into OrderBook[${security.name}] after $duration")
    }
    case Trade => {
      log.debug(s"Triggering market price recalculation for $security")

      recalculate() foreach {
        case t => {
          context.actorSelection(s"/user/" + t.bid.depot.accountNumber) ! BidResponse(t.bid, t.volume, t.price)
          context.actorSelection(s"/user/" + t.ask.depot.accountNumber) ! AskResponse(t.ask, t.volume, t.price)
        }
      }

    }

    case ListPrice => sender ! ListPriceResponse(currentPrice)
  }

  private def recalculate() : List[SuccessfulTrade] = {
    val (newAsks, newBids, successfulTrades) = doTrades(asks, bids)
    //log.info(s"Successful trades: $successfulTrades")

    (bids, asks) match {
      case (Nil, Nil) => log.debug("Not more bids and asks left!")
      case (x, Nil) => log.debug("Not more asks left!")
      case (Nil, x) => log.debug("Not more bids left!")
      case (b, a) => {
        val bidMax = b.map(_.price).max
        val askMin = a.map(_.price).min
        log.debug(s"bids max: $bidMax, asks min: $askMin")
      }
    }

    asks = newAsks
    bids = newBids
    currentPrice = calculatePrice(successfulTrades, currentPrice)

    log.debug(s"Current price for $security is $currentPrice")
    successfulTrades
  }
}
