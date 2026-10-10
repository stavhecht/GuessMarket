package api;

/**
 * The body of {@code POST /api/events/chat}: one line for an event's chat. Boxed for the
 * reason {@link ParticipateRequest} gives. There is no author: the server posts as the
 * logged-in user, so a client cannot speak for anyone else.
 */
public record ChatRequest(Integer eventId, String text) {
}
