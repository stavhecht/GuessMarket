package engine.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * One of the two outcomes of an event, holding the outstanding share count
 * (the {@code q_i} of the LMSR cost function).
 */
public class Option implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private long shares;

    public Option(String name) {
        this.name = name;
        this.shares = 0L;
    }

    public String getName() {
        return name;
    }

    public long getShares() {
        return shares;
    }

    /**
     * Adds newly issued shares.
     *
     * <p>Only ever called with a positive amount: this is the count of shares in
     * existence, and nothing destroys them. An LMSR purchase issues them, and in an order
     * book they are issued by the Market Maker's initial allocation and by minting — a
     * sale between two participants moves shares that already exist and leaves this alone.
     */
    public void addShares(long amount) {
        shares += amount;
    }
}
