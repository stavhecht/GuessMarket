package engine.exception;

/** No event with the requested id exists in the loaded file. */
public class EventNotFoundException extends EngineException {

    public EventNotFoundException(String message) {
        super(message);
    }
}
