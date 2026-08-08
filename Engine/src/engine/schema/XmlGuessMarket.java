package engine.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * The document root, {@code <Guess-Market>}.
 *
 * <pre>{@code
 * <Guess-Market>
 *   <GM-events>
 *     <GM-event .../>
 *   </GM-events>
 * </Guess-Market>
 * }</pre>
 */
@XmlRootElement(name = "Guess-Market")
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlGuessMarket {

    /**
     * {@code @XmlElementWrapper} names the {@code <GM-events>} container and
     * {@code @XmlElement} names each child inside it, so the wrapper element does not
     * need a class of its own — the root simply holds the events.
     */
    @XmlElementWrapper(name = "GM-events", required = true)
    @XmlElement(name = "GM-event")
    private List<XmlEvent> events = new ArrayList<>();

    /** Never {@code null}: an absent or empty {@code <GM-events>} reads as no events. */
    public List<XmlEvent> getEvents() {
        return events == null ? List.of() : events;
    }
}