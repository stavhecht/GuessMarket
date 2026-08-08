package engine.service;

import engine.exception.InvalidFileException;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Option;
import engine.schema.XmlCommission;
import engine.schema.XmlCommissionType;
import engine.schema.XmlEvent;
import engine.schema.XmlGuessMarket;
import engine.schema.XmlLmsr;
import engine.schema.XmlMarketMethod;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads an events file into domain objects, in three steps:
 *
 * <ol>
 *   <li>{@link #openXmlFile} — is there a readable {@code .xml} file at this path?</li>
 *   <li>{@link #parse} — JAXB turns it into the {@code engine.schema} classes, whose
 *       annotations hold the whole mapping from element to field.</li>
 *   <li>{@link #toEvents} — the rules are applied and {@link Event}s are built.</li>
 * </ol>
 *
 * <p>Step 3 exists because the XSD is far more permissive than the game: {@code comision}
 * is a plain {@code xs:int}, so 115 parses fine; {@code GM-option} has
 * {@code maxOccurs="2"} but no {@code minOccurs}, so a one-option event parses fine; and
 * nothing forbids two events sharing an id. Unmarshalling is lenient in the same way —
 * absent elements arrive as {@code null} and absent {@code xs:int}s as 0 — so every value
 * is checked here rather than trusted.
 *
 * <p>Nothing in this class touches engine state. A rejected file throws before a single
 * object reaches {@link EventManager}, so the previously loaded file survives intact.
 */
public class XmlEventLoader {

    private static final String XML_EXTENSION = ".xml";

    /** The file states a whole percentage, capped by the spec at 90. */
    private static final int MAX_COMMISSION_PERCENT = 90;
    private static final double PERCENT = 100.0;

    // Building the context is the expensive part of JAXB, so do it once per loader.
    private final JAXBContext context;

    public XmlEventLoader() {
        try {
            this.context = JAXBContext.newInstance(XmlGuessMarket.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Could not initialise the XML reader.", e);
        }
    }

    /**
     * @return the events in file order
     * @throws InvalidFileException if the file cannot be read, is not the expected XML,
     *                              or breaks one of the rules below
     */
    public List<Event> load(String path) {
        File file = openXmlFile(path);
        XmlGuessMarket document = parse(file);
        return toEvents(document.getEvents());
    }

    // --- step 1: the file ---

    private File openXmlFile(String path) {
        require(path != null && !path.isBlank(), "Please enter a file path.");
        require(path.toLowerCase(Locale.ROOT).endsWith(XML_EXTENSION),
                "The file must be an XML file (ending in %s).", XML_EXTENSION);

        File file = new File(path);
        require(file.isFile(), "No file found at: %s", path);
        require(file.canRead(), "File exists but cannot be read: %s", path);
        return file;
    }

    // --- step 2: XML to the annotated classes ---

    private XmlGuessMarket parse(File file) {
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Object parsed = unmarshaller.unmarshal(file);
            require(parsed instanceof XmlGuessMarket, "The file is not a Guess-Market events file.");
            return (XmlGuessMarket) parsed;
        } catch (JAXBException e) {
            // The linked exception is the SAX/parse error and says what is actually wrong.
            Throwable cause = e.getLinkedException() != null ? e.getLinkedException() : e;
            throw new InvalidFileException("The file could not be read as XML: " + cause.getMessage(), e);
        }
    }

    // --- step 3: the rules the schema cannot express ---

    private List<Event> toEvents(List<XmlEvent> xmlEvents) {
        require(!xmlEvents.isEmpty(), "The file contains no events.");

        List<Event> events = new ArrayList<>(xmlEvents.size());
        Set<Integer> idsSeen = new HashSet<>();
        for (XmlEvent xmlEvent : xmlEvents) {
            Event event = toEvent(xmlEvent);
            require(idsSeen.add(event.getId()),
                    "Duplicate event id in the file: %d ('%s').", event.getId(), event.getName());
            events.add(event);
        }
        return events;
    }

    private Event toEvent(XmlEvent xmlEvent) {
        String name = xmlEvent.getName();
        require(name != null && !name.isEmpty(), "An event is missing its 'name' attribute.");

        int id = xmlEvent.getId();
        require(id > 0, "Event '%s' has a missing or non-positive id: %d.", name, id);

        String description = xmlEvent.getDescription();
        require(description != null && !description.isEmpty(), "Event '%s' has no description.", name);

        XmlCommission commission = xmlEvent.getCommission();
        require(commission != null, "Event '%s' has no commission.", name);

        return new Event(
                id,
                name,
                description,
                commissionRate(commission.getPercent(), name),
                commissionMethod(commission.getType(), name),
                liquidity(xmlEvent.getMethod(), name),
                options(xmlEvent.getOptionNames(), name));
    }

    /** The file states a whole percentage; the domain wants the fraction it multiplies costs by. */
    private double commissionRate(int percent, String eventName) {
        require(percent >= 0 && percent <= MAX_COMMISSION_PERCENT,
                "Event '%s': commission must be between 0 and %d percent, got %d.",
                eventName, MAX_COMMISSION_PERCENT, percent);
        return percent / PERCENT;
    }

    private CommissionMethod commissionMethod(XmlCommissionType type, String eventName) {
        require(type != null, "Event '%s': commission 'type' must be %s.",
                eventName, XmlCommissionType.legalValues());
        return switch (type) {
            case ON_PURCHASE -> CommissionMethod.PER_PURCHASE;
            case ON_CLOSE -> CommissionMethod.ON_CLOSE;
        };
    }

    /** Reads b from {@code <GM-method><GM-LMSR><b>}, the only market maker the engine runs. */
    private double liquidity(XmlMarketMethod method, String eventName) {
        require(method != null, "Event '%s' has no market method.", eventName);

        XmlLmsr lmsr = method.getLmsr();
        require(lmsr != null, "Event '%s': the only supported market method is LMSR.", eventName);
        require(lmsr.getB() > 0, "Event '%s': b must be greater than 0, got %d.", eventName, lmsr.getB());
        return lmsr.getB();
    }

    private Option[] options(List<String> optionNames, String eventName) {
        // The schema allows 1 or 2 options (maxOccurs="2", minOccurs defaults to 1);
        // a binary market needs exactly 2.
        require(optionNames.size() == Event.OPTION_COUNT,
                "Event '%s' must have exactly %d options, found %d.",
                eventName, Event.OPTION_COUNT, optionNames.size());

        Option[] options = new Option[Event.OPTION_COUNT];
        for (int i = 0; i < optionNames.size(); i++) {
            String optionName = optionNames.get(i);
            require(optionName != null && !optionName.isEmpty(),
                    "Event '%s' has an option with an empty name.", eventName);
            options[i] = new Option(optionName);
        }
        require(!options[0].getName().equalsIgnoreCase(options[1].getName()),
                "Event '%s' has two options with the same name: %s.", eventName, options[0].getName());
        return options;
    }

    /**
     * States one rule per line: the condition that must hold, and what the user is told
     * when it does not. {@code args} fill the {@code %s}/{@code %d} slots in the message.
     */
    private static void require(boolean rule, String message, Object... args) {
        if (!rule) {
            throw new InvalidFileException(args.length == 0 ? message : String.format(message, args));
        }
    }
}