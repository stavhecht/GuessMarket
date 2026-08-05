package engine.exception;

/** A command that needs events was used before any file was successfully loaded. */
public class NoFileLoadedException extends EngineException {

    public NoFileLoadedException(String message) {
        super(message);
    }
}
