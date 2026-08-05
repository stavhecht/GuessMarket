package engine.dto;

import java.util.List;

/**
 * The full live state of one event.
 *
 * @param history               newest trade first
 * @param winningOptionName     {@code null} while the event is still active
 * @param finalSharesPerOption  {@code null} while the event is still active
 */
public record EventStatusView(int eventId,
                              String name,
                              List<OptionView> options,
                              double accountBalance,
                              double commissionCollected,
                              List<TradeView> history,
                              boolean closed,
                              String winningOptionName,
                              long[] finalSharesPerOption) {
}
