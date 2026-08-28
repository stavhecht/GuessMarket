package engine.loader;

import engine.exception.InvalidFileException;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Option;
import engine.model.TradingMethod;
import engine.model.User;
import engine.schema.XmlCommission;
import engine.schema.XmlCommissionType;
import engine.schema.XmlEvent;
import engine.schema.XmlEventRef;
import engine.schema.XmlGuessMarket;
import engine.schema.XmlLmsr;
import engine.schema.XmlMarketMethod;
import engine.schema.XmlOrderBook;
import engine.schema.XmlUser;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.ValidationEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads an events file into domain objects, in three steps:
 *
 * <ol>
 *   <li>{@link #openXmlFile} — is there a readable {@code .xml} file at this path?</li>
 *   <li>{@link #parse} — JAXB turns it into the {@code engine.schema} classes, whose
 *       annotations hold the whole mapping from element to field.</li>
 *   <li>{@link #toEvents} and {@link #toUsers} — the rules are applied, and {@link Event}s
 *       and {@link User}s are built.</li>
 * </ol>
 *
 * <p>Step 3 exists because the XSD is far more permissive than the game: {@code comision}
 * is a plain {@code xs:int}, so 115 parses fine; {@code GM-option} has
 * {@code maxOccurs="2"} but no {@code minOccurs}, so a one-option event parses fine;
 * nothing forbids two events sharing an id, two users sharing a name, or a user claiming
 * to be Market Maker for an event the file never defines. Unmarshalling is lenient in the same way —
 * absent elements arrive as {@code null} and absent {@code xs:int}s as 0 — so every value
 * is checked here rather than trusted.
 *
 * <p>Nothing in this class touches engine state. A rejected file throws before a single
 * object reaches {@link engine.service.EventManager}, so the previously loaded file survives intact.
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
     * @return the events and users in file order
     * @throws InvalidFileException if the file cannot be read, is not the expected XML,
     *                              or breaks one of the rules below
     */
    public LoadedMarket load(String path) {
        File file = openXmlFile(path);
        XmlGuessMarket document = parse(file);
        // Events first: the users' market-maker ids are only meaningful against them.
        List<Event> events = toEvents(document.getEvents());
        return new LoadedMarket(events, toUsers(document.getUsers(), events));
    }

    // --- step 1: the file ---

    private File openXmlFile(String rawPath) {
        require(rawPath != null, "Please enter a file path.");

        // Windows pastes the path in quotes, macOS pastes spaces escaped — either way the
        // extension check below has to see the real path, not the wrapping.
        String path = UserPath.normalize(rawPath);
        require(!path.isBlank(), "Please enter a file path.");
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
            unmarshaller.setEventHandler(XmlEventLoader::isRecoverable);
            Object parsed = unmarshaller.unmarshal(file);
            require(parsed instanceof XmlGuessMarket, "The file is not a Guess-Market events file.");
            return (XmlGuessMarket) parsed;
        } catch (JAXBException e) {
            // The linked exception is the SAX/parse error and says what is actually wrong.
            Throwable cause = e.getLinkedException() != null ? e.getLinkedException() : e;
            throw new InvalidFileException("The file could not be read as XML: " + cause.getMessage(), e);
        }
    }

    /**
     * Decides which of JAXB's complaints the unmarshaller may carry on past.
     *
     * <p>By default it carries on past all of them, which is the one place a bad file can
     * turn into plausible-looking data: a value it cannot convert is dropped and the field
     * keeps its Java default. For {@code <id>} and {@code <b>} that default is 0 and the
     * rules below reject it, but 0 is a perfectly legal commission — so
     * {@code <comision>abc</comision>} would load as a 0% event rather than be reported.
     *
     * <p>A failed conversion is exactly the case that arrives with a linked exception (the
     * {@code NumberFormatException} the parser caught), so those are refused and surface as
     * an {@link InvalidFileException} naming the line. Everything else — an unexpected
     * element, say — is let through on purpose: the rules below describe those in the
     * file's own vocabulary, and a message like "the only supported market method is LMSR"
     * beats JAXB's version of the same news.
     */
    private static boolean isRecoverable(ValidationEvent event) {
        return event.getLinkedException() == null;
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
                tradingMethod(xmlEvent.getMethod(), name),
                options(xmlEvent.getOptionNames(), name));
    }

    /**
     * Turns each {@code <GM-user>} into a {@link User}, checking the names against each
     * other and the market-maker ids against the events just built.
     *
     * @param events the file's events, already built — a market-maker id has to name one
     */
    private List<User> toUsers(List<XmlUser> xmlUsers, List<Event> events) {
        require(!xmlUsers.isEmpty(), "The file contains no users.");

        Set<Integer> eventIds = new HashSet<>();
        for (Event event : events) {
            eventIds.add(event.getId());
        }

        List<User> users = new ArrayList<>(xmlUsers.size());
        Set<String> namesSeen = new HashSet<>();
        // Event id to the name of the user already running it, so a second claim can say who has it.
        Map<Integer, String> marketMakerOf = new HashMap<>();
        for (XmlUser xmlUser : xmlUsers) {
            String name = xmlUser.getName();
            require(name != null && !name.isEmpty(), "A user is missing its 'name' attribute.");
            require(namesSeen.add(name.toLowerCase(Locale.ROOT)),
                    "Duplicate user name in the file: '%s'.", name);

            int cash = xmlUser.getInitialCash();
            require(cash >= 0, "User '%s' has a negative initial cash: %d.", name, cash);

            users.add(new User(name, cash, marketMakerEventIds(xmlUser, name, eventIds, marketMakerOf)));
        }
        requireOrderBooksAreFunded(events, users, marketMakerOf);
        return users;
    }

    /**
     * An order book takes its initial investment from the event's Market Maker, so the
     * file has to name one, and they have to be able to afford it.
     *
     * <p>This is the one rule that needs both halves of the file at once, and the reason
     * {@code initial-cash} is worth checking beyond "not negative": a market maker who
     * starts with less than the book asks for could never open it.
     *
     * <p>Only order-book events are asked this. An LMSR event's market maker puts nothing
     * in — the b·ln2 subsidy is the house's, not a user's.
     */
    private void requireOrderBooksAreFunded(List<Event> events,
                                            List<User> users,
                                            Map<Integer, String> marketMakerOf) {
        Map<String, User> byName = new HashMap<>();
        for (User user : users) {
            byName.put(user.getName(), user);
        }

        for (Event event : events) {
            if (!(event.getTradingMethod() instanceof TradingMethod.OrderBook orderBook)) {
                continue;
            }
            String makerName = marketMakerOf.get(event.getId());
            require(makerName != null,
                    "Event '%s' trades on an order book, but no user is its market maker.",
                    event.getName());

            User maker = byName.get(makerName);
            require(maker.getInitialCash() >= orderBook.initialInvestment(),
                    "User '%s' is market maker for event '%s', whose order book asks for an initial "
                            + "investment of %d — more than the %d they start with.",
                    maker.getName(), event.getName(), orderBook.initialInvestment(), maker.getInitialCash());
        }
    }

    /** The events one user runs as Market Maker: each has to exist, and to be theirs alone. */
    private List<Integer> marketMakerEventIds(XmlUser xmlUser,
                                              String userName,
                                              Set<Integer> eventIds,
                                              Map<Integer, String> marketMakerOf) {
        List<Integer> ids = new ArrayList<>();
        for (XmlEventRef ref : xmlUser.getMarketMakerEvents()) {
            int eventId = ref.getId();
            require(eventIds.contains(eventId),
                    "User '%s' is market maker for event %d, which the file does not define.",
                    userName, eventId);
            require(!ids.contains(eventId),
                    "User '%s' lists event %d twice as market maker.", userName, eventId);

            String heldBy = marketMakerOf.putIfAbsent(eventId, userName);
            require(heldBy == null,
                    "Event %d has two market makers: '%s' and '%s'.", eventId, heldBy, userName);
            ids.add(eventId);
        }
        return ids;
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

    /**
     * Reads {@code <GM-method>}, which the schema makes a choice of {@code <GM-LMSR>} and
     * {@code <GM-order-book>}.
     *
     * <p>A choice is what the schema says, not what unmarshalling enforces: a file naming
     * both fills both fields, and a file naming neither — or naming something else
     * entirely — fills neither. Hence exactly one is asked for here.
     */
    private TradingMethod tradingMethod(XmlMarketMethod method, String eventName) {
        require(method != null, "Event '%s' has no market method.", eventName);

        XmlLmsr lmsr = method.getLmsr();
        XmlOrderBook orderBook = method.getOrderBook();
        require(lmsr != null || orderBook != null,
                "Event '%s': the market method must be either GM-LMSR or GM-order-book.", eventName);
        require(lmsr == null || orderBook == null,
                "Event '%s' names both GM-LMSR and GM-order-book; it may have only one.", eventName);

        return lmsr != null ? lmsrMethod(lmsr, eventName) : orderBookMethod(orderBook, eventName);
    }

    /** Reads b from {@code <GM-LMSR><b>}: the liquidity the scoring rule prices with. */
    private TradingMethod lmsrMethod(XmlLmsr lmsr, String eventName) {
        require(lmsr.getB() > 0, "Event '%s': b must be greater than 0, got %d.", eventName, lmsr.getB());
        return new TradingMethod.Lmsr(lmsr.getB());
    }

    /**
     * Reads the {@code <GM-order-book>} attributes. The initial investment may be 0 — a
     * market maker who buys no initial shares — but the base value may not, so d is asked
     * to be positive the way b is.
     */
    private TradingMethod orderBookMethod(XmlOrderBook orderBook, String eventName) {
        Boolean allowMint = orderBook.getAllowMint();
        require(allowMint != null,
                "Event '%s': the order book's 'allow-mint' must be 'true' or 'false'.", eventName);
        require(orderBook.getInitialInvestment() >= 0,
                "Event '%s': the order book's initial investment cannot be negative, got %d.",
                eventName, orderBook.getInitialInvestment());
        require(orderBook.getD() > 0,
                "Event '%s': the order book's d must be greater than 0, got %d.", eventName, orderBook.getD());
        // The market maker is paid in pairs of shares, one pair per d invested, so an
        // investment that is not a whole number of pairs has no meaning.
        require(orderBook.getInitialInvestment() % orderBook.getD() == 0,
                "Event '%s': the order book's initial investment of %d must be a whole multiple of its base value %d.",
                eventName, orderBook.getInitialInvestment(), orderBook.getD());
        return new TradingMethod.OrderBook(allowMint, orderBook.getInitialInvestment(), orderBook.getD());
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