package engine.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * The per-event Market Maker account.
 *
 * <p>{@code balance} and {@code commissionCollected} are deliberately separate pots.
 * The balance exists to cover exactly one obligation — paying one currency unit per
 * winning share at settlement — so mixing operator revenue into it would destroy the
 * ability to check solvency with a single comparison.
 */
public class Account implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private double balance;
    private double commissionCollected;

    /** Money in: the initial subsidy, and the LMSR cost of every purchase. */
    public void deposit(double amount) {
        balance += amount;
    }

    /** Money out: payouts to winners, and any commission moved at settlement. */
    public void withdraw(double amount) {
        balance -= amount;
    }

    /** Records operator revenue. Does not touch {@link #getBalance()}. */
    public void addCommission(double amount) {
        commissionCollected += amount;
    }

    public double getBalance() {
        return balance;
    }

    public double getCommissionCollected() {
        return commissionCollected;
    }
}
