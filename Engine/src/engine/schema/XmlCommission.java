package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlValue;

/**
 * The {@code <comision type="on-purchase">50</comision>} element: a whole percentage
 * in the element's text, and when it is charged in the attribute.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlCommission {

    /**
     * {@code @XmlValue} binds the element's own text rather than a child element.
     * A primitive, so an empty element arrives as 0 — a legal commission, hence
     * the loader's range check treats it as such.
     */
    @XmlValue
    private int percent;

    /** {@code null} when the attribute is absent or holds a value outside {@link XmlCommissionType}. */
    @XmlAttribute(name = "type", required = true)
    private XmlCommissionType type;

    public int getPercent() {
        return percent;
    }

    public XmlCommissionType getType() {
        return type;
    }
}