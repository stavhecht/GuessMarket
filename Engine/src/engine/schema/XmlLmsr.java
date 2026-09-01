package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/** The {@code <GM-LMSR>} settings: just the liquidity parameter. */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlLmsr {

    /**
     * The LMSR liquidity parameter {@code b}: the larger it is, the less a purchase
     * moves the price. A primitive, so a missing {@code <b>} arrives as 0 and the
     * loader rejects it.
     */
    @XmlElement(name = "b", required = true)
    private int b;

    public int getB() {
        return b;
    }
}