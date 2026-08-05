package engine.service;

import engine.exception.InvalidFileException;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Option;
import engine.schema.Comision;
import engine.schema.GMEvent;
import engine.schema.GMEvents;
import engine.schema.GMLMSR;
import engine.schema.GMMethod;
import engine.schema.GMOptions;
import engine.schema.GuessMarket;

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
 * Reads an events file into domain objects.
 *
 * <p>JAXB (against {@code GM-EX1-Schema.xsd}, generated into {@code engine.schema})
 * handles the parsing; this class handles everything the schema cannot express. That
 * split matters, because the XSD is far more permissive than the game rules:
 *
 * <ul>
 *   <li>{@code comision} is a plain {@code xs:int} with no bounds — 115 is
 *       schema-valid, so the 0–90 rule is enforced here.</li>
 *   <li>{@code GM-option} is {@code maxOccurs="2"} but {@code minOccurs} defaults to 1,
 *       so a one-option event passes validation. The exactly-two rule is enforced here.</li>
 *   <li>Nothing in the schema forbids duplicate {@code id}s.</li>
 * </ul>
 *
 * <p>Unmarshalling is also lenient: absent elements arrive as {@code null} and absent
 * {@code xs:int}s arrive as 0, so every field is checked before use rather than trusted.
 *
 * <p>Nothing here mutates engine state — a failed load throws before a single object
 * reaches {@link EventManager}, so the previously loaded file survives intact.
 */
public class XmlEventLoader {

    private static final String XML_EXTENSION = ".xml";

    /** Attribute values of {@code <comision type="...">}. */
    private static final String TYPE_ON_PURCHASE = "on-purchase";
    private static final String TYPE_ON_CLOSE = "on-close";

    /** Commission in the file is a whole percentage, The spec caps it at 90. */
    private static final int MAX_COMMISSION_PERCENT = 90;
    private static final double PERCENT = 100.0;

    // Building the context is the expensive part of JAXB, so do it once per loader.
    private final JAXBContext context;

    public XmlEventLoader() {
        try {
            this.context = JAXBContext.newInstance(GuessMarket.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Could not initialise the XML reader.", e);
        }
    }

    public List<Event> load(String path) {
        validateExtension(path);

        File file = new File(path);
        if (!file.isFile()) {
            throw new InvalidFileException("No file found at: " + path);
        }
        if (!file.canRead()) {
            throw new InvalidFileException("File exists but cannot be read: " + path);
        }

        GuessMarket root = unmarshal(file);
        GMEvents gmEvents = root.getGMEvents();
        if (gmEvents == null || gmEvents.getGMEvent().isEmpty()) {
            throw new InvalidFileException("The file contains no events.");
        }

        List<Event> events = new ArrayList<>(gmEvents.getGMEvent().size());
        for (GMEvent gmEvent : gmEvents.getGMEvent()) {
            events.add(toDomain(gmEvent));
        }
        validateUniqueIds(events);
        return events;
    }

    private GuessMarket unmarshal(File file) {
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Object result = unmarshaller.unmarshal(file);
            if (!(result instanceof GuessMarket root)) {
                throw new InvalidFileException("The file is not a Guess-Market events file.");
            }
            return root;
        } catch (JAXBException e) {
            Throwable cause = e.getLinkedException() != null ? e.getLinkedException() : e;
            throw new InvalidFileException("The file could not be read as XML: " + cause.getMessage(), e);
        }
    }

    /** Copies one generated {@code GMEvent} into a domain {@link Event}, validating as it goes. */
    private Event toDomain(GMEvent gmEvent) {
        String name = joinName(gmEvent.getName());

        // id is a primitive int, so a missing <id> arrives as 0 rather than null.
        int id = gmEvent.getId();
        if (id <= 0) {
            throw new InvalidFileException("Event '" + name + "' has a missing or non-positive id: " + id + ".");
        }

        String description = gmEvent.getDescription();
        if (description == null || description.isBlank()) {
            throw new InvalidFileException("Event '" + name + "' has no description.");
        }

        Comision comision = gmEvent.getComision();
        if (comision == null) {
            throw new InvalidFileException("Event '" + name + "' has no commission.");
        }
        int percent = comision.getValue();
        validateCommission(percent, name);
        CommissionMethod method = parseCommissionMethod(comision.getType(), name);

        double b = readLiquidity(gmEvent.getGMMethod(), name);

        // Stored as a fraction (0.5), not a percentage (50), so it multiplies costs directly.
        return new Event(id, name, description.trim(), percent / PERCENT, method, b, readOptions(gmEvent, name));
    }

    /**
     * The schema declares {@code name} as {@code xs:list}, so JAXB hands back the value
     * split on whitespace — "Earth Quake on Dead Sea" arrives as five tokens. Rejoining
     * restores the name the file actually contained.
     */
    private String joinName(List<String> nameTokens) {
        if (nameTokens == null || nameTokens.isEmpty()) {
            throw new InvalidFileException("An event is missing its 'name' attribute.");
        }
        String name = String.join(" ", nameTokens).trim();
        if (name.isEmpty()) {
            throw new InvalidFileException("An event has an empty 'name' attribute.");
        }
        return name;
    }

    /**
     * Reads b from {@code <GM-method><GM-LMSR><b>}.
     *
     * <p>The {@code GM-method} wrapper exists so the schema can carry market-making
     * methods other than LMSR later; anything else gets a clear message.
     */
    private double readLiquidity(GMMethod gmMethod, String eventName) {
        if (gmMethod == null) {
            throw new InvalidFileException("Event '" + eventName + "' has no market method.");
        }
        GMLMSR lmsr = gmMethod.getGMLMSR();
        if (lmsr == null) {
            throw new InvalidFileException("Event '" + eventName + "': the only supported market method is LMSR.");
        }
        int b = lmsr.getB();
        if (b <= 0) {
            throw new InvalidFileException("Event '" + eventName + "': b must be greater than 0, got " + b + ".");
        }
        return b;
    }

    private Option[] readOptions(GMEvent gmEvent, String eventName) {
        GMOptions gmOptions = gmEvent.getGMOptions();
        if (gmOptions == null) {
            throw new InvalidFileException("Event '" + eventName + "' has no options.");
        }

        // The schema allows 1 or 2 options (maxOccurs="2", minOccurs defaults to 1);
        // a binary market needs exactly 2.
        List<String> names = gmOptions.getGMOption();
        if (names.size() != Event.OPTION_COUNT) {
            throw new InvalidFileException("Event '" + eventName + "' must have exactly " + Event.OPTION_COUNT
                    + " options, found " + names.size() + ".");
        }

        Option[] options = new Option[Event.OPTION_COUNT];
        for (int i = 0; i < names.size(); i++) {
            String optionName = names.get(i) == null ? "" : names.get(i).trim();
            if (optionName.isEmpty()) {
                throw new InvalidFileException("Event '" + eventName + "' has an option with an empty name.");
            }
            options[i] = new Option(optionName);
        }
        if (options[0].getName().equalsIgnoreCase(options[1].getName())) {
            throw new InvalidFileException("Event '" + eventName + "' has two options with the same name: "
                    + options[0].getName() + ".");
        }
        return options;
    }

    // --- rules the schema cannot express ---

    private void validateExtension(String path) {
        if (path == null || path.isBlank()) {
            throw new InvalidFileException("Please enter a file path.");
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith(XML_EXTENSION)) {
            throw new InvalidFileException("The file must be an XML file (ending in " + XML_EXTENSION + ").");
        }
    }

    private void validateUniqueIds(List<Event> events) {
        Set<Integer> seen = new HashSet<>();
        for (Event event : events) {
            if (!seen.add(event.getId())) {
                throw new InvalidFileException("Duplicate event id in the file: " + event.getId()
                        + " ('" + event.getName() + "').");
            }
        }
    }

    private void validateCommission(int percent, String eventName) {
        if (percent < 0 || percent > MAX_COMMISSION_PERCENT) {
            throw new InvalidFileException("Event '" + eventName + "': commission must be between 0 and "
                    + MAX_COMMISSION_PERCENT + " percent, got " + percent + ".");
        }
    }

    private CommissionMethod parseCommissionMethod(String raw, String eventName) {
        if (raw == null) {
            throw new InvalidFileException("Event '" + eventName + "': commission has no 'type'.");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case TYPE_ON_PURCHASE -> CommissionMethod.PER_PURCHASE;
            case TYPE_ON_CLOSE -> CommissionMethod.ON_CLOSE;
            default -> throw new InvalidFileException("Event '" + eventName + "': unknown commission type '"
                    + raw + "'. Expected " + TYPE_ON_PURCHASE + " or " + TYPE_ON_CLOSE + ".");
        };
    }
}
