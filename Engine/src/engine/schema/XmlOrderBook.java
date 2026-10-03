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
 * The correct spelling, {@code initial}, is read as well, since plenty of files use it.
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

    /**
     * What the Market Maker puts in to buy the event's initial shares. 0 is legal.
     * An {@code Integer}, unlike the other two, so that an absent attribute can be told
     * apart from a 0 and the correctly spelled one below is read in its place.
     */
    @XmlAttribute(name = "inital")
    private Integer initialInvestment;

    /** The same attribute spelled correctly, which files written by hand often use; see {@link #getInitialInvestment}. */
    @XmlAttribute(name = "initial")
    private Integer initialInvestmentSpelledRight;

    /** The event's base value. A primitive, so a missing {@code d} arrives as 0, which the loader rejects. */
    @XmlAttribute(name = "d", required = true)
    private int d;

    /** {@code null} when the attribute is absent. */
    public Boolean getAllowMint() {
        return allowMint;
    }

    /**
     * The investment under either spelling, {@code inital} or {@code initial}; 0 when the
     * file has neither, exactly as a missing {@code xs:int} always read. The schema's
     * spelling wins if it has both.
     */
    public int getInitialInvestment() {
        Integer investment = initialInvestment != null ? initialInvestment : initialInvestmentSpelledRight;
        return investment == null ? 0 : investment;
    }

    public int getD() {
        return d;
    }
}