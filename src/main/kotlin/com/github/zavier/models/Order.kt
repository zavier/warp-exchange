package com.github.zavier.models

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class OrderService(private val userAssetService: UserAssetService) {

    // 活动订单 orderId -> order
    private val activeOrders: ConcurrentMap<Long, Order> = ConcurrentHashMap()

    // 用户活动订单 userId -> orderId -> order
    private val userOrders: ConcurrentMap<Long, ConcurrentMap<Long, Order>> = ConcurrentHashMap()

    fun createOrder(
        sequenceId: Long,
        ts: LocalDateTime,
        orderId: Long,
        userId: Long,
        direction: Direction,
        price: BigDecimal,
        quantity: BigDecimal
    ): Order? {
        val userAsset = userAssetService.getUserAsset(userId, AssetEnum.USD)
        when(direction) {
            // 买入，冻结USD
            Direction.BUY -> {
                if (!userAsset.tryFreeze(price.multiply(quantity))) {
                    return null
                }
            }
            // 卖出，冻结BTC
            Direction.SELL -> {
                if (!userAsset.tryFreeze(quantity)) {
                    return null
                }
            }
        }

        val order = Order(orderId, sequenceId, userId, price, direction, OrderStatus.FULLY_FILLED, quantity, quantity, ts, ts)
        activeOrders[orderId] = order

        val orderMap = userOrders.computeIfAbsent(userId) {
            ConcurrentHashMap()
        }
        orderMap[orderId] = order
        return order
    }

    fun getOrder(orderId: Long): Order? {
        return activeOrders[orderId]
    }

    fun getUserOrders(userId: Long): ConcurrentMap<Long, Order>? {
        return userOrders[userId]
    }

    fun removeOrder(orderId: Long) {
        val removed = activeOrders.remove(orderId)
            ?: throw IllegalArgumentException("Order not found by orderId in active orders: $orderId")

        val userOrders = userOrders[removed.userId]
            ?: throw IllegalArgumentException("User orders not found by userId: ${removed.userId}")
        userOrders.remove(orderId) ?: throw IllegalArgumentException("Order not found by orderId in user orders: $orderId")
    }
}



data class OrderBook(
    val direction: Direction,
    val book: TreeMap<OrderKey, Order> = TreeMap(if (direction == Direction.BUY) OrderKey.SORT_BUY else OrderKey.SORT_SELL)
) {
    fun getFirst(): Order? {
        return book.firstEntry()?.value
    }

    fun remove(order: Order): Boolean {
        return book.remove(OrderKey(order.sequenceId, order.price)) != null
    }

    fun add(order: Order): Boolean {
        return book.put(OrderKey(order.sequenceId, order.price), order) == null
    }

}

data class OrderKey(val sequenceId: Long, val price: BigDecimal) {

    companion object {
        val SORT_SELL = object : Comparator<OrderKey> {
            override fun compare(o1: OrderKey?, o2: OrderKey?): Int {
                if (o1 == null || o2 == null) {
                    throw IllegalArgumentException("orderKey can't be null")
                }
                val cmp = o1.price.compareTo(o2.price)
                if (cmp != 0) {
                    return cmp
                }
                return o1.sequenceId.compareTo(o2.sequenceId)
            }
        }

        val SORT_BUY = object : Comparator<OrderKey> {
            override fun compare(o1: OrderKey?, o2: OrderKey?): Int {
                if (o1 == null || o2 == null) {
                    throw IllegalArgumentException("orderKey can't be null")
                }
                val cmp = o2.price.compareTo(o1.price)
                if (cmp != 0) {
                    return cmp
                }
                return o1.sequenceId.compareTo(o2.sequenceId)
            }
        }
    }
}

data class Order(
    val id: Long,
    val sequenceId: Long,
    val userId: Long,

    val price: BigDecimal,
    val direction: Direction,
    var status: OrderStatus,

    val quantity: BigDecimal,
    // 未成交数量
    var unfilledQuantity: BigDecimal,

    // 所属交易对
//    val symbolId: Long,

    val createdAt: LocalDateTime = LocalDateTime.now(),
    var updateAt: LocalDateTime = LocalDateTime.now()
) {
    fun updateOrder(unfilledQuantity: BigDecimal, status: OrderStatus, ts: LocalDateTime) {
        this.unfilledQuantity = unfilledQuantity
        this.status = status
        this.updateAt = ts
    }
}


enum class Direction {
    BUY,
    SELL,
}

enum class OrderStatus {
    FULLY_FILLED,
    PARTIAL_FILLED,
    PENDING
}