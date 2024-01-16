package com.github.zavier.models

import com.github.zavier.models.Transfer.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class TradingEngineService(
    private val userAssetService: UserAssetService,
    private val orderService: OrderService,
    private val matchEngine: MatchEngine,
    private val clearingService: ClearingService,
    private val logger: Logger = LoggerFactory.getLogger(TradingEngineService::class.java)
) {
    fun processMessage(messages: List<AbstractEvent>) {
        for (message in messages) {
            processEvent(message)
        }
    }

    private fun processEvent(event: AbstractEvent) {
        when(event) {
            is OrderRequestEvent -> createOrder(event)
            is OrderCancelEvent -> cancelOrder(event)
            is TransferEvent -> transfer(event)
        }
    }

    private fun createOrder(event: OrderRequestEvent) {
        val now = LocalDateTime.now()
        val orderId = event.sequenceId * 10000 + (now.year * 100 + now.month.value)
        val order = orderService.createOrder(
            event.sequenceId,
            event.createAt,
            orderId,
            event.userId,
            event.direction,
            event.price,
            event.quantity
        )
        if (order == null) {
            logger.warn("create order failed.")
            return
        }
        // 撮合
        val result = matchEngine.processOrder(event.sequenceId, order)
        // 清算
        clearingService.clearMatchResult(result)
    }

    private fun cancelOrder(event: OrderCancelEvent): Nothing = TODO()

    private fun transfer(event: TransferEvent): Nothing = TODO()

}

open class AbstractEvent

class OrderRequestEvent(
    val sequenceId: Long,
    val createAt: LocalDateTime,
    val userId: Long,
    val direction: Direction,
    val price: BigDecimal,
    val quantity: BigDecimal
) : AbstractEvent()

class OrderCancelEvent : AbstractEvent()
class TransferEvent : AbstractEvent()


class UserAssetService {
    // 用户ID -> (资产ID -> Asset)
    private val userAssets: ConcurrentMap<Long, ConcurrentMap<AssetEnum, Asset>> = ConcurrentHashMap()

    fun getUserAsset(userId: Long, assetId: AssetEnum): UserAsset {
        val asset = userAssets[userId]?.get(assetId) ?: Asset()
        return UserAsset(userId, assetId, asset)
    }
}

data class UserAsset(val userId: Long, val assetId: AssetEnum, val asset: Asset) {

    fun transfer(toUserAsset: UserAsset, transfer: Transfer, amount: BigDecimal) {
        if (!tryTransfer(transfer, toUserAsset, amount, true)) {
            throw RuntimeException("Transfer failed")
        }
    }

    fun tryFreeze(amount: BigDecimal): Boolean {
        return tryTransfer(AVAILABLE_TO_FROZEN, this, amount, true)
    }

    fun unfreeze(amount: BigDecimal) {
        if (!tryTransfer(FROZEN_TO_AVAILABLE, this, amount, true)) {
            throw RuntimeException("Unfreeze failed")
        }
    }

    private fun tryTransfer(
        transfer: Transfer,
        toUserAsset: UserAsset,
        amount: BigDecimal,
        checkBalance: Boolean
    ): Boolean {
        if (amount.signum() < 0) {
            throw IllegalArgumentException("Negative amount")
        }
        val fromAsset = this.asset
        val toAsset = toUserAsset.asset

        when(transfer) {
            AVAILABLE_TO_AVAILABLE -> {
                // 余额检查
                if (checkBalance && !fromAsset.hasSufficientAvailable(amount)) {
                    return false
                }
                fromAsset.subtractAvailable(amount)
                toAsset.addAvailable(amount)
                return true
            }
            AVAILABLE_TO_FROZEN -> {
                if (checkBalance && !fromAsset.hasSufficientAvailable(amount)) {
                    return false
                }
                fromAsset.subtractAvailable(amount)
                toAsset.addFrozen(amount)
                return true
            }
            FROZEN_TO_AVAILABLE -> {
                if (checkBalance && !fromAsset.hasSufficientAvailable(amount)) {
                    return false
                }
                fromAsset.subtractFrozen(amount)
                toAsset.addAvailable(amount)
                return true
            }
            else -> throw IllegalArgumentException("invalid transfer type: $transfer")
        }
    }
}

data class Asset(
    var available: BigDecimal = BigDecimal.ZERO,
    var frozen: BigDecimal = BigDecimal.ZERO
) {
    fun subtractAvailable(amount: BigDecimal) {
        available -= amount
    }

    fun addAvailable(amount: BigDecimal) {
        available += amount
    }

    fun subtractFrozen(amount: BigDecimal) {
        frozen -= amount
    }

    fun addFrozen(amount: BigDecimal) {
        frozen += amount
    }

    fun hasSufficientAvailable(amount: BigDecimal): Boolean {
        return available >= amount
    }

}

enum class Transfer {
    // 可用转可用:
    AVAILABLE_TO_AVAILABLE,
    // 可用转冻结:
    AVAILABLE_TO_FROZEN,
    // 冻结转可用:
    FROZEN_TO_AVAILABLE;
}

enum class AssetEnum {
    USD,
    BTC
}