package com.tyrell.castor.commons.data.typing

import com.tyrell.castor.commons.data.typing.MarketSignal.MarketClose
import com.tyrell.castor.commons.data.typing.MarketSignal.UntilMarketClose
import com.tyrell.castor.commons.data.typing.StockAction.BuyAction.MarketBuy
import com.tyrell.castor.commons.data.typing.StockAction.DoNothing
import com.tyrell.castor.commons.data.typing.StockAction.SellAction.MarketSell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingle
import reactor.kotlin.extra.math.average
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.KClass


market { tinvest(token = "<SOME_SECRET_TOKEN>") }
stocks listOf("APPL", "BABA", "BIIB").map { StockId.ticker(it) }

stok.install(OpenFigi)

val strat = strategy { // TODO

    val StrategyProperties.enterOnMarketOpen: Boolean by properties
    val StrategyProperties.tradedTickers: List<String> by properties.named("tradedTickers")

    settings.market {

        tickers += tradedTickers.map { Ticker(it) }
    }

    lifecycle {
        
        start {
            on { enterOnMarketOpen and TimeCondition.MarketOpen }

            on { TimeCondition.MarketOpenFor(8 hours) and TimeCondition.UntilMarketClose(3 hours) }
        }

        sleep {
            on { PortfolioCondition.BoughtStock }
            wakeup { TimeCondition.Never }
        }

        stop {
            on { EnvironmentCondition.UntilMarketClose { it < 45 minutes } }
        }
    }
}


fun stok(closure: StrategyBuilder.() -> Unit) {
    TODO()
}

// Pretend that class is not abstract
suspend fun StrategyBuilder.MyStockStrategy() {

    fun MarketScope.`60 days slice`(): MarketSlice = market.sliceSince(now.minusDays(60))

    suspend fun `60 days average price`(stock: StockId): StockAmount.Price = `60 days slice`()
        .map { market -> market[stock].price.value }
        .asFlux().average().awaitSingle()
        .let { average -> StockAmount.Price(average) }

    on(UntilMarketClose(Duration.ofMinutes(10))) { DoNothing }

    on(MarketClose) { DoNothing }

    on(StockSignal.PriceSignal::class) { DoNothing }

    onNewPrice action@{
        val timeSinceSignal = Duration.between(now, market.state.date)
        val position: PortfolioPosition = portfolio[signal.stock]
        when {
            (timeSinceSignal.toSeconds() > 10) -> return@action DoNothing
            (signal.stock !in portfolio) -> return@action DoNothing
            (signal.price < position.averagePrice) -> return@action DoNothing
        }

        if (signal.price < `60 days average price`(signal.stock)) {
            return@action MarketSell(signal.stock, StockAmount.PortfolioRatio.all())
        }

        return@action MarketBuy(signal.stock, StockAmount.PortfolioRatio(0.5))
    }
}


interface StrategyScope : CoroutineScope {

    val now: LocalDateTime
}

interface MarketScope : StrategyScope {

    val portfolio: Portfolio

    val market: Market
}

interface SignalScope<out S: StockSignal> : MarketScope {

    val signal: S
}

interface StrategyBuilder : StrategyScope {

    suspend fun <S: StockSignal> StrategyScope.on(signal: KClass<S>, handler: suspend SignalScope<S>.() -> StockAction)

    suspend fun <S: StockSignal> StrategyScope.on(signal: S, handler: suspend SignalScope<S>.() -> StockAction)

    suspend fun <S: MarketSignal> MarketSignal.on(signal: S, handler: suspend MarketScope.() -> StockAction)

    suspend fun onNewPrice(handler: suspend SignalScope<StockSignal.PriceSignal>.() -> StockAction)

    suspend fun onStockSignal(handler: suspend StrategyScope.(StockSignal) -> StockAction)
}

interface Market {

    val state: MarketState

    fun slice(range: DateRange): MarketSlice

    fun slice(range: DateTimeRange): MarketSlice

    fun sliceSince(value: LocalDateTime): MarketSlice
}

interface MarketSlice : Flow<MarketState>

data class MarketState(
    val date: LocalDateTime,

    val priceByStock: Map<StockId, StockState>
) {

    operator fun contains(value: StockId): Boolean = TODO()

    operator fun get(value: StockId): StockState = TODO()
}

data class StockState(
    val stock: StockId,

    val price: StockAmount.Price
)

interface Portfolio : Iterable<Pair<StockId, StockAmount.Amount>> {

    operator fun contains(stock: StockId): Boolean

    operator fun get(stock: StockId): PortfolioPosition

    override fun iterator(): Iterator<Pair<StockId, StockAmount.Amount>>
}

data class PortfolioPosition(
    val stock: StockId,
    val amount: StockAmount.Amount,
    val averagePrice: StockAmount.Price
)

// ==========

sealed interface MarketSignal {

    object MarketClose: StockSignal

    data class UntilMarketClose(val duration: Duration): StockSignal
}

sealed interface StockSignal : MarketSignal {

    data class StockDeal(val id: StockId, val amount: StockAmount<*>) : StockSignal

    data class PriceSignal(val stock: StockId, val price: StockAmount.Price): StockSignal
}

// ========== Actions

sealed interface StockAction {

    object DoNothing : StockAction

    sealed class BuyAction(val stock: StockId, val amount: StockAmount<*>) : StockAction {

        class MarketBuy(stock: StockId, amount: StockAmount<*>) : BuyAction(stock, amount)

        // ...
    }

    sealed class SellAction(val stock: StockId, val amount: StockAmount<*>) : StockAction {

        class MarketSell(stock: StockId, amount: StockAmount<*>) : BuyAction(stock, amount)

        // ...
    }
}

// ========== Stock ID

sealed interface StockId {

    data class Ticker(val value: String) : StockId

    data class Figi(val value: String) : StockId

}

// ========== Stock Amount

sealed class StockAmount<T : Comparable<T>>(value: T) : Comparable<StockAmount<T>> {

    val value: T = value

    override fun compareTo(other: StockAmount<T>): Int = this.value.compareTo(other.value)

    class Amount(value: Int) : StockAmount<Int>(value)

    class Price(value: BigDecimal) : StockAmount<BigDecimal>(value) {

        constructor(value: Double): this(value.toBigDecimal())
    }

    class PortfolioRatio(ratio: BigDecimal) : StockAmount<BigDecimal>(ratio) {

        constructor(ratio: Double) : this(ratio.toBigDecimal())

        companion object {

            fun all(): PortfolioRatio = PortfolioRatio(BigDecimal.ONE)
        }
    }
}

operator fun LocalDate.rangeTo(value: LocalDate): DateRange {
    TODO()
}

operator fun LocalDate.rangeTo(value: LocalDateTime): DateRange = (this..value.toLocalDate())

operator fun LocalDateTime.rangeTo(value: LocalDateTime): DateTimeRange {
    TODO()
}

operator fun LocalDateTime.rangeTo(value: LocalDate): DateTimeRange = (this..value.atStartOfDay())

data class DateRange(
    override val start: LocalDate,
    override val endInclusive: LocalDate,
    val step: Instant
) : ClosedRange<LocalDate>, Iterable<LocalDate> {

    override fun iterator(): Iterator<LocalDate> = TODO()
}

data class DateTimeRange(
    override val start: LocalDateTime,
    override val endInclusive: LocalDateTime,
    val step: Instant
) : ClosedRange<LocalDateTime>, Iterable<LocalDateTime> {

    override fun iterator(): Iterator<LocalDateTime> = TODO()
}