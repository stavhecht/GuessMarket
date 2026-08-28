package engine.exception;

/**
 * The command acts on behalf of somebody — a purchase, an order, an account view — and
 * no user has been selected to act as yet.
 */
public class NoUserSelectedException extends EngineException {

    public NoUserSelectedException(String message) {
        super(message);
    }
}
