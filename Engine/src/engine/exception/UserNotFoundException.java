package engine.exception;

/** No user of that name was loaded from the file. */
public class UserNotFoundException extends EngineException {

    public UserNotFoundException(String message) {
        super(message);
    }
}
