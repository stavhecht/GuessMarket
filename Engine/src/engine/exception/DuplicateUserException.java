package engine.exception;

/** A user of that name, in some letter case, is already in the market. */
public class DuplicateUserException extends EngineException {

    public DuplicateUserException(String message) {
        super(message);
    }
}