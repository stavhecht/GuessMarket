package api;

/**
 * The body of {@code POST /api/events/close}: which option won. Boxed for the reason
 * {@link ParticipateRequest} gives, which matters most here: an unboxed winner left out of
 * the body would settle the event on option 0.
 */
public record CloseRequest(Integer eventId, Integer winningOptionIndex) {
}
