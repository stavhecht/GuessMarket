package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * The {@code <GM-order-book allow-mint="true" inital="100" d="1"/>} settings: the second
 * market method a file may name, alongside {@link XmlLmsr}.
 *
 * <p>{@code inital} is spelled the way the XSD spells it, exactly as {@code comision} is:
 * these classes describe the file, so a typo in the schema is copied rather than corrected.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlOrderBook {

    /**
     * Whether the event mints new share pairs when no matching order is available.
     *
     * <p>A {@code Boolean} rather than a primitive so an absent attribute arrives as
     * {@code null} and the loader can say so, instead of silently reading as false.
     */
    @XmlAttribute(name = "allow-mint", required = true)
    private Boolean allowMint;

    /** What the Market Maker puts in to buy the event's initial shares. 0 is legal. */
    @XmlAttribute(name = "inital", required = true)
    private int initialInvestment;

    /** The event's base value. A primitive, so a missing {@code d} arrives as 0, which the loader rejects. */
    @XmlAttribute(name = "d", required = true)
    private int d;

    /** {@code null} when the attribute is absent. */
    public Boolean getAllowMint() {
        return allowMint;
    }

    public int getInitialInvestment() {
        return initialInvestment;
    }

    public int getD() {
        return d;
    }
}