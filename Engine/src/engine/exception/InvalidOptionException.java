package engine.exception;

/** The option index is outside the event's two outcomes. */
public class InvalidOptionException extends EngineException {

    public InvalidOptionException(String message) {
        super(message);
    }
}
