package engine.exception;

/** A chat message that cannot be posted: empty, too long, or not a single line of text. */
public class InvalidMessageException extends EngineException {

    public InvalidMessageException(String message) {
        super(message);
    }
}