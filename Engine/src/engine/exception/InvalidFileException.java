package engine.exception;

/** The XML file is missing, unreadable, malformed, or fails a content rule. */
public class InvalidFileException extends EngineException {

    public InvalidFileException(String message) {
        super(message);
    }

    public InvalidFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
