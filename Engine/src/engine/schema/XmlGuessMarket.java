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
 *   <GM-users>
 *     <GM-user .../>
 *   </GM-users>
 * </Guess-Market>
 * }</pre>
 *
 * <p>The schema declares the two sections as an {@code xs:all}, so a file may write them
 * in either order; JAXB matches by name and does not care.
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

    /** The same wrapper trick for {@code <GM-users>}. */
    @XmlElementWrapper(name = "GM-users", required = true)
    @XmlElement(name = "GM-user")
    private List<XmlUser> users = new ArrayList<>();

    /** Never {@code null}: an absent or empty {@code <GM-events>} reads as no events. */
    public List<XmlEvent> getEvents() {
        return events == null ? List.of() : events;
    }

    /** Never {@code null}: an absent or empty {@code <GM-users>} reads as no users. */
    public List<XmlUser> getUsers() {
        return users == null ? List.of() : users;
    }
}