package engine.exception;

/** A saved session could not be written, or could not be read back. */
public class StateFileException extends EngineException {

    public StateFileException(String message, Throwable cause) {
        super(message, cause);
    }
}