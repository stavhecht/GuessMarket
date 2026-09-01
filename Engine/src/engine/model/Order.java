package engine.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * One order resting in, or passing through, an {@link OrderBook}: somebody's offer to
 * buy or sell a quantity of one option at a price.
 *
 * <p>A class rather than a record because {@link #getRemaining()} shrinks as the order
 * fills. Everything else about it is fixed at placement: an order is never repriced, it is
 * filled or it waits.
 *
 * <p>The money or shares backing it are set aside on the owner's account the moment it
 * rests ({@code User.reserve} / {@code User.lockShares}), which is what lets a later fill
 * commit without asking whether the counterparty can still pay.
 */
public class Order implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int sequence;
    private final String userName;
    private final int optionIndex;
    private final OrderSide side;
    private final double price;
    private final long quantity;

    private long remaining;

    public Order(int sequence, String userName, int optionIndex, OrderSide side, double price, long quantity) {
        this.sequence = sequence;
        this.userName = userName;
        this.optionIndex = optionIndex;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remaining = quantity;
    }

    /** 1-based position in the event's order log, and the tie-breaker for equal prices. */
    public int getSequence() {
        return sequence;
    }

    public String getUserName() {
        return userName;
    }

    public int getOptionIndex() {
        return optionIndex;
    }

    public OrderSide getSide() {
        return side;
    }

    /** What the owner is willing to pay (BUY) or accept (SELL) per share. */
    public double getPrice() {
        return price;
    }

    /** What was asked for originally. */
    public long getQuantity() {
        return quantity;
    }

    /** What is still unfilled, and so still backed by a reservation. */
    public long getRemaining() {
        return remaining;
    }

    public long getFilled() {
        return quantity - remaining;
    }

    public boolean isExhausted() {
        return remaining == 0;
    }

    /** Takes {@code amount} off what is left. The caller has already moved the money and the shares. */
    public void fill(long amount) {
        remaining -= amount;
    }
}