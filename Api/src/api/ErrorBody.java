package api;

/**
 * What the server answers with on any status but success: the engine's own sentence, and
 * the simple name of the exception it came from, for a client that wants to tell them
 * apart. The message is what the user sees, unchanged, so it is written for them.
 */
public record ErrorBody(String error, String type) {
}
