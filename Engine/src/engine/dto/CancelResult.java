package engine.dto;

/**
 * The outcome of withdrawing an order from a book: which one it was, and what its owner got
 * back to use again.
 *
 * @param sequence     the order's number in the event's order log
 * @param side         {@code BUY} or {@code SELL}
 * @param cancelled    the shares it was still waiting for; what had filled stays filled
 * @param cashReleased what a buy had set aside for those shares and their commission, now
 *                     available again; 0 for a sell, whose {@code cancelled} shares are what
 *                     is freed instead
 */
public record CancelResult(int eventId,
                           int sequence,
                           String side,
                           String optionName,
                           double price,
                           long cancelled,
                           double cashReleased) {
}