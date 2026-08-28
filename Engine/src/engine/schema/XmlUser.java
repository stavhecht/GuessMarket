package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code <GM-user>}: who they are, what they start with, and the events they run as
 * Market Maker.
 *
 * <pre>{@code
 * <GM-user name="Tikva">
 *   <initial-cash>10000</initial-cash>
 *   <GM-mareket-maker>
 *     <event id="1"/>
 *     <event id="4"/>
 *   </GM-mareket-maker>
 * </GM-user>
 * }</pre>
 *
 * <p>{@code GM-mareket-maker} is spelled the way the XSD spells it — see {@link XmlOrderBook}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlUser {

    @XmlAttribute(name = "name", required = true)
    @XmlJavaTypeAdapter(NormalizedTextAdapter.class)
    private String name;

    /** A primitive, so a missing {@code <initial-cash>} arrives as 0 — which is a legal balance. */
    @XmlElement(name = "initial-cash", required = true)
    private int initialCash;

    /**
     * The wrapper names {@code <GM-mareket-maker>} and the inner annotation names each
     * {@code <event>} inside it, so the container needs no class of its own. Optional:
     * a user who runs no event simply omits it.
     */
    @XmlElementWrapper(name = "GM-mareket-maker")
    @XmlElement(name = "event")
    private List<XmlEventRef> marketMakerEvents = new ArrayList<>();

    public String getName() {
        return name;
    }

    public int getInitialCash() {
        return initialCash;
    }

    /** Never {@code null}: an absent or empty {@code <GM-mareket-maker>} reads as no events. */
    public List<XmlEventRef> getMarketMakerEvents() {
        return marketMakerEvents == null ? List.of() : marketMakerEvents;
    }
}