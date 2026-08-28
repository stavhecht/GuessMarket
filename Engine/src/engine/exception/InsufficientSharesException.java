package engine.exception;

/**
 * The user is trying to sell shares they do not have free — they hold fewer than that, or
 * the ones they hold are already promised to an order resting in the book.
 */
public class InsufficientSharesException extends EngineException {

    public InsufficientSharesException(String message) {
        super(message);
    }
}
