package api;

/**
 * The body of {@code POST /api/events/order}: a limit order in an order book. Boxed for the
 * reason {@link ParticipateRequest} gives.
 *
 * @param side {@code BUY} or {@code SELL}, the names of {@link engine.model.OrderSide}
 */
public record OrderRequest(Integer eventId, Integer optionIndex, String side, Double price, Long quantity) {
}
