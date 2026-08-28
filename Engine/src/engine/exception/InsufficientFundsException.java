package engine.exception;

/** The user cannot pay for this: their available cash is less than it costs. */
public class InsufficientFundsException extends EngineException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
