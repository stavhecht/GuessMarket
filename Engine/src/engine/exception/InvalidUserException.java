package engine.exception;

/** A user who cannot be opened as described: no name left after trimming, or negative cash. */
public class InvalidUserException extends EngineException {

    public InvalidUserException(String message) {
        super(message);
    }
}