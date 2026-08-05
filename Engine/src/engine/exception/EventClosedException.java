package engine.exception;

/** The event has already been settled, so it can't be traded on or closed again. */
public class EventClosedException extends EngineException {

    public EventClosedException(String message) {
        super(message);
    }
}
