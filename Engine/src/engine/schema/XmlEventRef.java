package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * One {@code <event id="2"/>} inside a user's {@code <GM-mareket-maker>}: a pointer to an
 * event defined elsewhere in the file, not an event of its own.
 *
 * <p>The id is an attribute, which is the only reason this needs a class at all: the
 * element itself is empty.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlEventRef {

    /** A primitive, so a missing {@code id} arrives as 0; no event has that id, and the loader says so. */
    @XmlAttribute(name = "id", required = true)
    private int id;

    public int getId() {
        return id;
    }
}