package api;

/**
 * The body of {@code POST /api/me/funds}: how much to put into the logged-in user's account.
 * Boxed for the reason {@link ParticipateRequest} gives.
 */
public record LoadFundsRequest(Double amount) {
}
