package engine.model;

/**
 * When the market operator takes its cut, and who pays it.
 *
 * <p>{@link #PER_PURCHASE} ({@code on-purchase} in the XML) charges the buyer on every
 * trade, calculated from the purchase price. {@link #ON_CLOSE} ({@code on-close})
 * charges nothing up front and is deducted from the winners' payout at settlement —
 * losing participants never pay it.
 *
 * <p>Because the on-close commission comes out of money already owed to winners rather
 * than out of the market maker's account, it is always affordable no matter how
 * one-sided the market got.
 */
public enum CommissionMethod {
    PER_PURCHASE,
    ON_CLOSE
}
