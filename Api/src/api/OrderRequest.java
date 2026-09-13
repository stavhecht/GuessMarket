package api;

/**
 * The body of {@code POST /api/events/order}: a limit order in an order book.
 *
 * @param side {@code BUY} or {@code SELL}, the names of {@link engine.model.OrderSide}
 */
public record OrderRequest(int eventId, int optionIndex, String side, double price, long quantity) {
}
