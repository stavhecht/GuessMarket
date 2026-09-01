package engine.model;

/**
 * Which way an order goes.
 *
 * <p>The two are not mirror images. A {@link #BUY} can create shares (if the opposite
 * option has a buyer willing to pay the rest of the base value, a pair is minted), while a
 * {@link #SELL} only ever moves shares that already exist, from someone who holds them.
 */
public enum OrderSide {
    BUY,
    SELL
}