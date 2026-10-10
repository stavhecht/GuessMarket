package api;

/** The body of {@code POST /api/login}: the name to be known by. */
public record LoginRequest(String name) {
}
