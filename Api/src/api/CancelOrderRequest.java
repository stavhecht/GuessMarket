package api;

/**
 * The body of {@code POST /api/events/order/cancel}: the event whose book to withdraw the
 * logged-in user's most recent waiting order from. Boxed for the reason
 * {@link ParticipateRequest} gives.
 */
public record CancelOrderRequest(Integer eventId) {
}