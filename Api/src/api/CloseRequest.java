package api;

/** The body of {@code POST /api/events/close}: which option won. */
public record CloseRequest(int eventId, int winningOptionIndex) {
}
