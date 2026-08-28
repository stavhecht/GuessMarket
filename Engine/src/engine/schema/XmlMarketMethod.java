package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * The {@code <GM-method>} element — which market maker prices this event.
 *
 * <p>The schema makes it an {@code xs:choice} of {@code <GM-LMSR>} and
 * {@code <GM-order-book>}, so exactly one of the two fields below is filled in. It is
 * only a choice in the schema, though: unmarshalling on its own would happily fill both
 * from a file that names both, and fills neither from a file that names something else.
 * Which is why the loader asks for exactly one rather than trusting what arrives.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlMarketMethod {

    /** {@code null} unless the file chose LMSR. */
    @XmlElement(name = "GM-LMSR")
    private XmlLmsr lmsr;

    /** {@code null} unless the file chose the order book. */
    @XmlElement(name = "GM-order-book")
    private XmlOrderBook orderBook;

    public XmlLmsr getLmsr() {
        return lmsr;
    }

    public XmlOrderBook getOrderBook() {
        return orderBook;
    }
}