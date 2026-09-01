package engine.dto;

/**
 * What a purchase would cost if it were made right now, asked before committing to it,
 * and answered without moving anything.
 *
 * <p>An LMSR cost is a log-sum-exp of the outstanding share counts, so a UI cannot work it
 * out from the quoted price the way it can for an order book, where the cost of {@code n}
 * shares at a limit price is just the product. That is the whole reason this exists: the
 * screen has to be able to show the price of a trade before the trade.
 *
 * @param commission what would be charged now: 0 under {@code ON_CLOSE}, where the
 *                   operator's cut is taken out of the winnings instead
 * @param priceAfter what the option would be quoted at once those shares existed
 */
public record PurchaseQuote(String optionName,
                            long shares,
                            double sharesCost,
                            double commission,
                            double totalCost,
                            double priceAfter) {
}
