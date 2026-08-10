package engine.dto;

import engine.model.Trade;

import java.util.List;

/**
 * The full live state of one event.
 *
 * @param history               newest trade first; {@link Trade} is immutable, so the
 *                              log is passed through rather than copied into a view
 * @param winningOptionName     {@code null} while the event is still active
 * @param finalSharesPerOption  {@code null} while the event is still active
 */
public record EventStatusView(int eventId,
                              String name,
                              List<OptionView> options,
                              double accountBalance,
                              double commissionCollected,
                              List<Trade> history,
                              boolean closed,
                              String winningOptionName,
                              long[] finalSharesPerOption) {
}
