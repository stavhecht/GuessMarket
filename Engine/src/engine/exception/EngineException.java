package engine.exception;

/**
 * Base class for every error the engine reports to the UI.
 *
 * <p>Unchecked on purpose: the UI catches this once, at the top of each command
 * handler, and prints {@link #getMessage()}. Every message is written to be shown
 * to a user as-is.
 */
public class EngineException extends RuntimeException {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
