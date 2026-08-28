package engine.model;

import java.io.Serializable;

/**
 * One line of an order book's history: shares changing hands, or being created.
 *
 * <p>A mint is written as two of these — one per buyer — because the two sides pay
 * different prices for different options and there is no seller to name. Each line then
 * says the same thing in both cases: this user got this many shares of this option at
 * this price.
 *
 * @param kind       {@link Kind#MATCH} when a buyer met a seller, {@link Kind#MINT} when a
 *                   pair was created out of two buyers
 * @param seller     {@code null} for a mint — the shares came from nowhere, and the money
 *                   went to the event's account rather than to a person
 * @param commission what the buyer paid the Market Maker on top, 0 under {@code ON_CLOSE}
 */
public record BookTrade(int sequence,
                        Kind kind,
                        int optionIndex,
                        String optionName,
                        double price,
                        long quantity,
                        String buyer,
                        String seller,
                        double commission) implements Serializable {

    public enum Kind {
        MATCH,
        MINT
    }

    /** What the shares themselves cost, before commission. */
    public double amount() {
        return price * quantity;
    }

    public double totalPaid() {
        return amount() + commission;
    }
}