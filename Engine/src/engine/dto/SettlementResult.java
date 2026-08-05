package engine.dto;

/**
 * The outcome of closing an event.
 *
 * @param commissionMoved     the operator's cut, carved out of the winners' payout;
 *                            always 0 under {@code PER_PURCHASE}, where it was already
 *                            taken from buyers trade by trade
 * @param totalPaidToWinners  what the winners actually receive — gross winnings (one
 *                            unit per winning share) minus {@code commissionMoved}
 */
public record SettlementResult(int eventId,
                               String winningOptionName,
                               long[] sharesPerOption,
                               double commissionMoved,
                               double totalPaidToWinners) {
}
