package engine.model;

import java.io.Serializable;

/**
 * How an event is traded: the market maker that prices it, and its settings.
 *
 * <p>Sealed with one record per method, so the settings of each live together and can
 * only be read after saying which method they belong to: a {@code b} can't be taken
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

        /**
         * What it costs to open this market: {@code b·ln2}, the scoring rule's provable
         * worst-case loss, which the Market Maker puts into the event's account and which
         * covers every payout the rule can ever owe.
         *
         * <p>The same number the scoring rule gives for a market at zero shares, its
         * {@code cost(0, 0, b)}, and it is written here rather than in
         * {@code engine.service.LmsrCalculator} for the reason {@link OrderBook#openingPrice()}
         * is: it is a property of the settings, and the loader has to be able to ask what an
         * event will cost its maker without being able to see into {@code service}.
         */
        public double subsidy() {
            return b * Math.log(2);
        }
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

        /**
         * Where the market opens: what the Market Maker's initial allocation cost them a
         * share, on both options, since a pair costs the whole base value, so each side of it is
         * worth half.
         *
         * <p>Half the base value on each side is also the only split that says nothing about
         * which outcome is likelier, which is exactly what is known before anyone has traded,
         * the same thing an LMSR event's 0.5 says. Nothing is <em>quoted</em> at it: the
         * allocation is the maker's to sell when they choose. {@code MarketEngine} reports it
         * so a price chart can start there rather than at the first transaction.
         */
        public double openingPrice() {
            return d / 2.0;
        }
    }
}