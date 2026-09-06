package engine.dto;

/**
 * The outcome of closing an event.
 *
 * @param commissionMoved     the operator's cut, carved out of the winners' payout;
 *                            always 0 under {@code PER_PURCHASE}, where it was already
 *                            taken from buyers trade by trade
 * @param totalPaidToWinners  what the winners actually receive: gross winnings (one
 *                            unit per winning share) minus {@code commissionMoved}
 * @param subsidyReturned     what was left in an LMSR event's account once the winners
 *                            were paid, handed back to the Market Maker who put the
 *                            subsidy up; always 0 for an order book, whose account is
 *                            emptied exactly by the payout, and 0 for an LMSR event with
 *                            no Market Maker, where the remainder has nobody to go to
 */
public record SettlementResult(int eventId,
                               String winningOptionName,
                               long[] sharesPerOption,
                               double commissionMoved,
                               double totalPaidToWinners,
                               double subsidyReturned) {
}
