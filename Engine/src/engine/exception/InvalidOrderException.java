package engine.exception;

/** The order itself does not make sense: its price is outside (0, d], or its quantity is not positive. */
public class InvalidOrderException extends EngineException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
