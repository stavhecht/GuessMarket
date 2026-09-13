package api;

/** The body of {@code POST /api/events/participate}: an LMSR purchase, indices 0-based as the engine counts. */
public record ParticipateRequest(int eventId, int optionIndex, long shares) {
}
