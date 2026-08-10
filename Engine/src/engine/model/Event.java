package engine.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The aggregate root: an event, its two outcomes, its Market Maker account and its
 * trade history. Everything that mutates market state goes through here, so the
 * invariants (trade numbering, close-once) can't be broken from outside.
 */
public class Event implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** This market is binary by construction. */
    public static final int OPTION_COUNT = 2;

    private final int id;
    private final String name;
    private final String description;
    private final double commissionRate;
    private final CommissionMethod commissionMethod;
    private final double b;
    private final Option[] options;
    private final Account MMaccount = new Account();
    private final List<Trade> trades = new ArrayList<>();

    private EventStatus status = EventStatus.ACTIVE;
    private Integer winningOptionIndex;
    private int tradeCounter;

    public Event(int id,
                 String name,
                 String description,
                 double commissionRate,
                 CommissionMethod commissionMethod,
                 double b,
                 Option[] options) {
        if (options == null || options.length != OPTION_COUNT) {
            throw new IllegalArgumentException("An event must have exactly " + OPTION_COUNT + " options.");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.commissionRate = commissionRate;
        this.commissionMethod = commissionMethod;
        this.b = b;
        this.options = options.clone();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public double getCommissionRate() {
        return commissionRate;
    }

    public CommissionMethod getCommissionMethod() {
        return commissionMethod;
    }

    /** The LMSR liquidity parameter. */
    public double getB() {
        return b;
    }

    public Option getOption(int index) {
        return options[index];
    }

    public Account getMMAccount() {
        return MMaccount;
    }

    public List<Trade> getTrades() {
        return Collections.unmodifiableList(trades);
    }

    public EventStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == EventStatus.ACTIVE;
    }

    /** {@code null} until the event is closed. */
    public Integer getWinningOptionIndex() {
        return winningOptionIndex;
    }

    /**
     * Builds and appends a trade, stamping it with the next sequence number.
     *
     * <p>The {@link Trade} is constructed here rather than passed in so the counter
     * never leaks out of the aggregate and can't hand the same sequence to two trades.
     */
    public Trade recordTrade(String optionName, long shares, double sharesCost, double commission) {
        Trade trade = new Trade(++tradeCounter, optionName, shares, sharesCost, commission);
        trades.add(trade);
        return trade;
    }

    public void close(int winningOptionIndex) {
        this.status = EventStatus.CLOSED;
        this.winningOptionIndex = winningOptionIndex;
    }
}
