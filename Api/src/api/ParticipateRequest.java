package api;

/**
 * The body of {@code POST /api/events/participate}: an LMSR purchase, indices 0-based as the engine counts.
 *
 * <p>Boxed, like every field of every request here, so that a field the body left out
 * arrives as {@code null} and is refused by the server, rather than as a {@code 0} that
 * reads exactly like a choice: option 0 is a real option.
 */
public record ParticipateRequest(Integer eventId, Integer optionIndex, Long shares) {
}
