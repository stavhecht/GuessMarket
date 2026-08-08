package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * The {@code <GM-method>} element — which market maker prices this event.
 *
 * <p>It exists as a level of its own so the file can name other methods later;
 * today LMSR is the only child the schema defines, and the only one the engine runs.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlMarketMethod {

    /** {@code null} when the file names a method the engine does not implement. */
    @XmlElement(name = "GM-LMSR", required = true)
    private XmlLmsr lmsr;

    public XmlLmsr getLmsr() {
        return lmsr;
    }
}