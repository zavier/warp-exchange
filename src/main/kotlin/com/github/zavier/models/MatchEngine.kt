package com.github.zavier.models

import java.math.BigDecimal

class MatchEngineGroup {
    val engines = mutableMapOf<Long, MatchEngine>()
    fun processOrder(sequenceId: Long, order: Order): MatchResult = TODO()
}

class MatchEngine {
    private val buyBook: OrderBook = OrderBook(Direction.BUY)
    private val sellBook: OrderBook = OrderBook(Direction.SELL)
    private var marketPrice: BigDecimal = BigDecimal.ZERO // 最新市场价
    private var sequenceId: Long = 0 // 上次处理的Sequence ID

    fun processOrder(sequenceId: Long, order: Order): MatchResult {
        return when(order.direction) {
            Direction.BUY -> processOrder(sequenceId, order, this.sellBook, this.buyBook)
            Direction.SELL -> processOrder(sequenceId, order, this.buyBook, this.sellBook)
        }
    }

    /**
     * takerOrder 吃单：正在处理的订单
     * maker 挂单：买卖盘中的订单
     */
    private fun processOrder(sequenceId: Long, takerOrder: Order, makerBook: OrderBook, anotherBook: OrderBook): MatchResult {
        this.sequenceId = sequenceId;
        val ts = takerOrder.createdAt
        val matchResult = MatchResult(takerOrder)
        var takeUnfilledQuantity = takerOrder.quantity
        while (true) {
            val makerOrder = makerBook.getFirst()
            if (makerOrder == null) {
                break
            }
            // 买：价格无法成交
            if (takerOrder.direction == Direction.BUY && takerOrder.price < makerOrder.price) {
                break
            }
            // 卖：价格无法成交
            if (takerOrder.direction == Direction.SELL && takerOrder.price > makerOrder.price) {
                break
            }

            // 最新成交价格
            this.marketPrice = makerOrder.price
            // 可以成交的数量
            val matchedQuantity = takeUnfilledQuantity.min(makerOrder.unfilledQuantity)
            // 成交记录
            matchResult.add(makerOrder.price, matchedQuantity, makerOrder)
            // 更新成交后订单数量
            takeUnfilledQuantity -= matchedQuantity
            val makerUnfilledQuantity = makerOrder.unfilledQuantity - matchedQuantity
            if (makerUnfilledQuantity.signum() == 0) {
                // 对手盘完成成交，订单结束
                makerOrder.updateOrder(makerUnfilledQuantity, OrderStatus.FULLY_FILLED, ts)
                makerBook.remove(makerOrder)
            } else {
                // 部分成交
                makerOrder.updateOrder(makerUnfilledQuantity, OrderStatus.PARTIAL_FILLED, ts)
            }

            // Taker订单完成成交，结束
            if (takeUnfilledQuantity.signum() == 0) {
                takerOrder.updateOrder(takeUnfilledQuantity, OrderStatus.FULLY_FILLED, ts)
                break
            }
        }

        // 未完全成交
        if (takeUnfilledQuantity > BigDecimal.ZERO) {
            takerOrder.updateOrder(
                takeUnfilledQuantity,
                if (takeUnfilledQuantity == takerOrder.quantity) OrderStatus.PENDING else OrderStatus.PARTIAL_FILLED,
                ts
            )
            anotherBook.add(takerOrder)
        }

        return matchResult
    }
}

data class MatchResult(val takerOrder: Order, val matchDetails: MutableList<MatchDetailRecord> = mutableListOf()) {
    fun add(price: BigDecimal, quantity: BigDecimal, makerOrder: Order) {
        matchDetails.add(MatchDetailRecord(price, quantity, takerOrder, makerOrder))
    }
}

data class MatchDetailRecord(val price: BigDecimal, val quantity: BigDecimal, val takerOrder: Order, val makerOrder: Order)