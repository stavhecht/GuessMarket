package engine.model;

import java.io.Serializable;

/**
 * One of the two outcomes of an event, holding the outstanding share count
 * (the {@code q_i} of the LMSR cost function).
 */
public class Option implements Serializable {

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

    /** Adds newly issued shares. Only ever called with a positive amount — market has no selling. */
    public void addShares(long amount) {
        shares += amount;
    }
}
