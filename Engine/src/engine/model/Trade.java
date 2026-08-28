package engine.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * An immutable record of one purchase, kept for the event's history.
 *
 * @param sequence   1-based position in this event's trade log
 * @param optionName the outcome that was bought
 * @param shares     how many shares were issued
 * @param sharesCost what the shares themselves cost (LMSR), before commission
 * @param commission commission charged at purchase time; always 0 for {@link CommissionMethod#ON_CLOSE}
 * @param createdAt  time when the trade was created
 */
public record Trade(int sequence,
                    String optionName,
                    long shares,
                    double sharesCost,
                    double commission,
                    Instant createdAt) implements Serializable {

    public Trade(int sequence,
                 String optionName,
                 long shares,
                 double sharesCost,
                 double commission) {
        this(sequence, optionName, shares, sharesCost, commission, Instant.now());
    }

    /** What the participant actually handed over. */
    public double totalPaid() {
        return sharesCost + commission;
    }
}