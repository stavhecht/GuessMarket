package api;

/** The body of {@code POST /api/me/funds}: how much to put into the logged-in user's account. */
public record LoadFundsRequest(double amount) {
}