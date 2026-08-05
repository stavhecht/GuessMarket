package engine.exception;

/** A purchase asked for a non-positive number of shares. */
public class InvalidShareAmountException extends EngineException {

    public InvalidShareAmountException(String message) {
        super(message);
    }
}
