package engine.model;

import java.io.Serializable;

/**
 * How an event is traded: the market maker that prices it, and its settings.
 *
 * <p>Sealed with one record per method, so the settings of each live together and can
 * only be read after saying which method they belong to — a {@code b} can't be taken
 * from an order-book event, and the compiler checks that a {@code switch} over the two
 * handles both.
 *
 * <p>Which method an event uses is fixed by the file and never changes, so both records
 * are immutable values; {@link Event} holds one and the rest of the market state.
 */
public sealed interface TradingMethod extends Serializable {

    /**
     * Hanson's LMSR, priced by {@code engine.service.LmsrCalculator}.
     *
     * @param b the liquidity parameter: the larger it is, the less a purchase moves the price
     */
    record Lmsr(double b) implements TradingMethod {
    }

    /**
     * An order book, where participants trade with each other rather than with a
     * scoring rule.
     *
     * @param allowMint         whether the event mints new share pairs when no order matches
     * @param initialInvestment what the Market Maker puts in for the event's initial shares
     * @param d                 the event's base value
     */
    record OrderBook(boolean allowMint, int initialInvestment, int d) implements TradingMethod {
    }
}