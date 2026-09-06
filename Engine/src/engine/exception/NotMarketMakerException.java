package engine.exception;

/**
 * Only an event's Market Maker may seal it and declare the winning option, and the
 * selected user is not it.
 */
public class NotMarketMakerException extends EngineException {

    public NotMarketMakerException(String message) {
        super(message);
    }
}
