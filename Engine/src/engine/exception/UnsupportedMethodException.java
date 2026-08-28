package engine.exception;

/**
 * The command does not apply to the way this event trades — an LMSR price asked of an
 * order-book event, or an order placed in an event that has no book. The file is fine and
 * so is the event; the command belongs to the other kind of market.
 */
public class UnsupportedMethodException extends EngineException {

    public UnsupportedMethodException(String message) {
        super(message);
    }
}
