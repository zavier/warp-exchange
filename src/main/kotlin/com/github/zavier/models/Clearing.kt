package com.github.zavier.models

class ClearingService(
    private val userAssetService: UserAssetService,
    private val orderService: OrderService) {

    fun clearMatchResult(result: MatchResult) {
        val taker = result.takerOrder
        when(taker.direction) {
            Direction.BUY -> {
                // 买入时，按maker价格成交
                for (detail in result.matchDetails) {
                    val maker = detail.makerOrder
                    val matched = detail.quantity
                    if (taker.price > maker.price) {
                        // 买入比报价低时，差额退回解冻
                        val unfreezeQuote = (taker.price - maker.price) * matched
                        userAssetService.getUserAsset(taker.userId, AssetEnum.USD).unfreeze(unfreezeQuote)
                    }
                    // 买方支付USD, 卖方支付BTC, 互相转入对方账户
                    val makerAssetUSD = userAssetService.getUserAsset(maker.userId, AssetEnum.USD)
                    val takerAssetUSD = userAssetService.getUserAsset(taker.userId, AssetEnum.USD)
                    takerAssetUSD.transfer(makerAssetUSD, Transfer.FROZEN_TO_AVAILABLE, matched)

                    val makerAssetBTC = userAssetService.getUserAsset(maker.userId, AssetEnum.BTC)
                    val takerAssetBTC = userAssetService.getUserAsset(taker.userId, AssetEnum.BTC)
                    makerAssetBTC.transfer(takerAssetBTC, Transfer.FROZEN_TO_AVAILABLE, matched)

                    // 删除完成成交的maker
                    if (maker.unfilledQuantity.signum() == 0) {
                        orderService.removeOrder(maker.id)
                    }
                }
                // 删除完成成交的taker
                if (taker.unfilledQuantity.signum() == 0) {
                    orderService.removeOrder(taker.id)
                }
            }
            Direction.SELL -> {
                for (detail in result.matchDetails) {
                    val maker = detail.makerOrder
                    val matched = detail.quantity

                    // 卖家BTC转入买方账户
                    val takerBTCAsset = userAssetService.getUserAsset(taker.userId, AssetEnum.BTC)
                    val makerBTCAsset = userAssetService.getUserAsset(maker.userId, AssetEnum.BTC)
                    takerBTCAsset.transfer(makerBTCAsset, Transfer.FROZEN_TO_AVAILABLE, matched)

                    // 买方USD转入卖方账户
                    val takerUSDAsset = userAssetService.getUserAsset(taker.userId, AssetEnum.USD)
                    val makerUSDAsset = userAssetService.getUserAsset(maker.userId, AssetEnum.USD)
                    makerUSDAsset.transfer(takerUSDAsset, Transfer.FROZEN_TO_AVAILABLE, matched)

                    if (maker.unfilledQuantity.signum() == 0) {
                        orderService.removeOrder(maker.id)
                    }
                }
                if (taker.unfilledQuantity.signum() == 0) {
                    orderService.removeOrder(taker.id)
                }
            }
        }
    }

    fun clearCancelOrder(order: Order) {
        when(order.direction) {
            Direction.BUY ->
                userAssetService.getUserAsset(order.userId, AssetEnum.USD).unfreeze(order.price * order.unfilledQuantity)
            Direction.SELL ->
                userAssetService.getUserAsset(order.userId, AssetEnum.BTC).unfreeze(order.unfilledQuantity)
        }
        orderService.removeOrder(order.id)
    }
}
