package engine.exception;

/**
 * An event someone asked the engine to create does not describe a market.
 *
 * <p>The same rules a file is held to, applied to an event typed in at runtime — the loader
 * enforces them on its way in from XML and cannot be reused here, because none of this comes
 * from a file. {@code InvalidFileException} would name a file that does not exist.
 */
public class InvalidEventException extends EngineException {

    public InvalidEventException(String message) {
        super(message);
    }
}
