package engine.exception;

/** A sum of money that cannot be loaded: not positive, or not a number at all. */
public class InvalidAmountException extends EngineException {

    public InvalidAmountException(String message) {
        super(message);
    }
}