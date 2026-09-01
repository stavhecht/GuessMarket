package engine.service;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.FillView;
import engine.dto.HoldingView;
import engine.dto.OptionBookView;
import engine.dto.OptionView;
import engine.dto.OrderBookStatusView;
import engine.dto.OrderLineView;
import engine.dto.OrderResult;
import engine.dto.PurchaseQuote;
import engine.dto.PurchaseResult;
import engine.dto.SettlementResult;
import engine.dto.UserView;
import engine.exception.EventClosedException;
import engine.exception.InsufficientFundsException;
import engine.exception.InvalidEventException;
import engine.exception.InvalidOptionException;
import engine.exception.InvalidShareAmountException;
import engine.exception.NoFileLoadedException;
import engine.exception.NoUserSelectedException;
import engine.exception.NotMarketMakerException;
import engine.exception.StateFileException;
import engine.exception.UnsupportedMethodException;
import engine.loader.LoadedMarket;
import engine.loader.UserPath;
import engine.loader.XmlEventLoader;
import engine.model.Account;
import engine.model.BookTrade;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Option;
import engine.model.Order;
import engine.model.OrderBook;
import engine.model.OrderSide;
import engine.model.Trade;
import engine.model.TradingMethod;
import engine.model.User;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The front: the only engine class the UI imports.
 *
 * <p>Two rules hold throughout: every DTO is built here (the UI never constructs one),
 * and no value is ever rounded (formatting is the UI's job, so the account identity
 * {@code balance == subsidy + Σ costs − payouts} stays exact).
 */
public class MarketEngine {

    /** Appended to the path the user gives, so they never have to type an extension. */
    public static final String STATE_EXTENSION = ".gm";

    /** What one share of the winning option pays at settlement in an LMSR market. */
    private static final double LMSR_SHARE_VALUE = 1.0;

    /** Commission is stated as a whole percentage, the same way a file states it. */
    private static final double PERCENT = 100.0;

    /** Not final: loading a saved session replaces the whole collection at once. */
    private EventManager eventManager = new EventManager();
    private final LmsrCalculator lmsr = new LmsrCalculator();
    private final XmlEventLoader loader = new XmlEventLoader();
    private final OrderExecutor executor = new OrderExecutor();
    private boolean fileLoaded =  false;

    /**
     * Who the console is acting as. Held by name rather than by reference so that loading
     * a session cannot leave it pointing at a user from the file before it, and
     * deliberately not part of the saved state, so a restored session asks who you are again.
     */
    private String currentUserName;

    /**
     * Whatever the front end asked to be kept with the last session it loaded, which for the desktop
     * app is its theme. Opaque here: the engine writes it out and hands it back, and never looks
     * inside it. Not itself saved, since it only describes the session that was.
     */
    private String restoredUiState;

    /**
     * Loads a new events file, replacing everything currently in memory.
     * If the file is rejected, the previously loaded state is left untouched.
     */
    public void loadEventsFile(String path) {
        LoadedMarket market = loader.load(path);
        eventManager.load(market.events(), market.users());
        eventManager.applyInitialSubsidies(lmsr);
        eventManager.applyInitialAllocations();
        currentUserName = null;   // the new file has its own users
        fileLoaded = true;
    }

    /**
     * Creates an LMSR event in the market already loaded, run by whoever is acting.
     *
     * <p>The event is subsidised the moment it exists, {@code b·ln2} into its own account,
     * the provable worst case of the scoring rule, so it is solvent before anyone can trade
     * on it. That money comes from the house, not from the creator, and it is the one
     * documented exception to the conservation identity.
     *
     * @param b the liquidity parameter; a file can only state a whole number, but nothing in
     *          the market requires one
     * @return the event as the UI sees it
     */
    public EventView createLmsrEvent(String name, String description, int commissionPercent,
                                     CommissionMethod commissionMethod, List<String> optionNames,
                                     double b) {
        requireFileLoaded();
        User creator = requireSelectedUser();

        // --- validate; nothing has been touched yet ---
        String eventName = normalise(name);
        String eventDescription = normalise(description);
        Option[] options = validateDefinition(eventName, eventDescription, commissionPercent,
                commissionMethod, optionNames);
        requireEvent(b > 0, "b must be greater than 0, got " + b + ".");

        Event event = new Event(eventManager.nextEventId(), eventName, eventDescription,
                commissionPercent / PERCENT, commissionMethod, new TradingMethod.Lmsr(b), options);

        // --- commit ---
        eventManager.addEvent(event);
        creator.addMarketMakerEvent(event.getId());
        // This event alone: applyInitialSubsidies would pay every LMSR event a second time.
        eventManager.subsidise(event, lmsr);
        return viewOf(event);
    }

    /**
     * Creates an order-book event in the market already loaded, run and funded by whoever is
     * acting.
     *
     * <p>Unlike an LMSR event this one costs its creator money: they are its Market Maker, so
     * {@code initialInvestment} leaves their balance for the event's account and comes back
     * to them as {@code initialInvestment / d} shares of each option, which they then hold.
     * Nothing is created or destroyed: the identity holds across this call exactly.
     *
     * <p>The book itself opens empty either way: the shares are the creator's to sell when
     * they choose to, at whatever price they name, so an investment of 0 differs only in
     * leaving them with nothing to sell.
     *
     * @return the event as the UI sees it
     */
    public EventView createOrderBookEvent(String name, String description, int commissionPercent,
                                          CommissionMethod commissionMethod, List<String> optionNames,
                                          int initialInvestment, int d, boolean allowMint) {
        requireFileLoaded();
        User creator = requireSelectedUser();

        // --- validate; nothing has been touched yet ---
        String eventName = normalise(name);
        String eventDescription = normalise(description);
        Option[] options = validateDefinition(eventName, eventDescription, commissionPercent,
                commissionMethod, optionNames);
        requireEvent(d > 0, "the base value d must be greater than 0, got " + d + ".");
        requireEvent(initialInvestment >= 0,
                "the initial investment cannot be negative, got " + initialInvestment + ".");
        requireEvent(initialInvestment % d == 0, "the initial investment of " + initialInvestment
                + " must be a whole multiple of the base value " + d + ".");
        // The creator's balance now, not the cash the file started them with: they may have
        // spent it since, and it is today's money that funds the book.
        requireEvent(creator.getBalance() >= initialInvestment,
                "'" + creator.getName() + "' holds "
                        + String.format("%.2f", creator.getBalance())
                        + " and cannot fund an initial investment of " + initialInvestment + ".");

        Event event = new Event(eventManager.nextEventId(), eventName, eventDescription,
                commissionPercent / PERCENT, commissionMethod,
                new TradingMethod.OrderBook(allowMint, initialInvestment, d), options);

        // --- commit ---
        eventManager.addEvent(event);
        // Before the allocation: it is the market-maker link that entitles them to fund it.
        creator.addMarketMakerEvent(event.getId());
        // This event alone: applyInitialAllocations would re-fund every other one.
        eventManager.allocateInitial(event, creator);
        return viewOf(event);
    }

    /**
     * The rules both kinds of created event share, applied before anything is built.
     *
     * <p>These are the loader's rules, restated. {@code XmlEventLoader} cannot be reused for
     * them because it is shaped around a file (it parses, then validates what it parsed)
     * and none of this came from one. Keep the two in step: an event typed into the desktop
     * app and an event read from XML have to be the same kind of thing.
     *
     * @return the two options, ready for the event
     */
    private Option[] validateDefinition(String name, String description, int commissionPercent,
                                        CommissionMethod commissionMethod, List<String> optionNames) {
        requireEvent(!name.isEmpty(), "an event needs a name.");
        requireEvent(!description.isEmpty(), "event '" + name + "' needs a description.");
        requireEvent(commissionMethod != null, "event '" + name + "' needs a commission method.");
        requireEvent(commissionPercent >= 0 && commissionPercent <= Event.MAX_COMMISSION_PERCENT,
                "commission must be between 0 and " + Event.MAX_COMMISSION_PERCENT
                        + " percent, got " + commissionPercent + ".");
        requireEvent(optionNames != null && optionNames.size() == Event.OPTION_COUNT,
                "an event must have exactly " + Event.OPTION_COUNT + " options.");

        Option[] options = new Option[Event.OPTION_COUNT];
        for (int i = 0; i < Event.OPTION_COUNT; i++) {
            String optionName = normalise(optionNames.get(i));
            requireEvent(!optionName.isEmpty(), "an option cannot have an empty name.");
            options[i] = new Option(optionName);
        }
        requireEvent(!options[0].getName().equalsIgnoreCase(options[1].getName()),
                "both options are called '" + options[0].getName() + "'.");
        return options;
    }

    /** Reads like the loader's {@code require}, and throws the runtime twin of its exception. */
    private static void requireEvent(boolean condition, String problem) {
        if (!condition) {
            throw new InvalidEventException("Cannot create the event: " + problem);
        }
    }

    /**
     * Trims and squeezes runs of whitespace, the way {@code NormalizedTextAdapter} does on
     * the way out of a file, so a name typed with a stray double space becomes the same
     * event name an XML file would have produced. {@code null} reads as empty and is then
     * refused by the caller with a message about the field rather than a
     * {@code NullPointerException}.
     */
    private static String normalise(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Writes the current session, every event with its prices, balances and trade
     * history, to {@code path} plus {@value #STATE_EXTENSION}.
     *
     * <p>{@link EventManager} holds all of it and is {@link java.io.Serializable}, so one
     * {@code writeObject} captures the lot. The user supplies the path without an
     * extension, as the brief asks; typing one anyway is tolerated rather than doubled.
     *
     * @return the path of the file actually written, for the UI to report
     */
    public String saveState(String path) {
        return saveState(path, null);
    }

    /**
     * The same, carrying one string of the front end's own back with it: the desktop app
     * saves the theme the window was wearing, so reopening the session reopens the look.
     *
     * <p>The engine never reads it. It is written as a second object <em>after</em> the
     * state, which is what keeps the file backwards compatible in both directions: a
     * session saved before this existed simply runs out of file where the string would be
     * (see {@link #loadState}), and a session saved with one still begins with the
     * {@link EventManager} that older code expects.
     *
     * @param uiState anything the caller wants back on load, or {@code null} for nothing.
     *                {@code String} rather than an object so the engine cannot end up
     *                depending on a UI type
     */
    public String saveState(String path, String uiState) {
        File file = stateFile(path);
        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(file))) {
            stream.writeObject(eventManager);
            stream.writeObject(uiState);
            return file.getPath();
        } catch (FileNotFoundException e) {
            // What this really means here: the folder is missing, or is not writable.
            throw new StateFileException("Could not write " + file.getPath()
                    + ". Check that the folder exists and can be written to.", e);
        } catch (IOException e) {
            throw new StateFileException("Could not save to " + file.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Restores a session previously written by {@link #saveState}, replacing whatever is
     * loaded now. An unreadable file leaves the current session untouched, because the
     * field is only reassigned once the read has succeeded.
     *
     * <p>Anything the caller stored through {@link #saveState(String, String)} is waiting in
     * {@link #getRestoredUiState()} afterwards.
     *
     * @return the path of the file actually read, for the UI to report
     */
    public String loadState(String path) {
        File file = stateFile(path);
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file))) {
            EventManager restored = (EventManager) stream.readObject();
            // Nothing has been built here, since this state was deserialised rather than
            // loaded, so the
            // one invariant the money paths lean on is re-checked before it is adopted. Read
            // into a local first: a session saved before every event had to have a market
            // maker must leave the current one untouched, like any other rejected file.
            restored.requireEveryEventHasAMarketMaker();
            eventManager = restored;
            currentUserName = null;   // the restored session has its own users
            restoredUiState = readUiState(stream);
            fileLoaded = true;
            return file.getPath();
        } catch (FileNotFoundException e) {
            throw new StateFileException("There is no saved session at " + file.getPath() + ".", e);
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            throw new StateFileException("Could not load a saved session from " + file.getPath()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * The front end's own string out of the session just restored, or {@code null} if that
     * file carried none, which is the case for every session saved before
     * {@link #saveState(String, String)} existed, and for every one the console saves.
     *
     * <p>Read from the field rather than returned by {@code loadState} so the console, which
     * has nothing to restore, can keep ignoring it.
     */
    public String getRestoredUiState() {
        return restoredUiState;
    }

    /**
     * The trailing string, if the file has one. A session written before it existed ends
     * after the {@link EventManager}, so hitting the end of the file here is the older
     * format rather than a corrupt one, and reads as "nothing stored".
     */
    private static String readUiState(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        try {
            Object stored = stream.readObject();
            return stored instanceof String text ? text : null;
        } catch (EOFException end) {
            return null;
        }
    }

    /**
     * The path is normalised first ({@link UserPath}): a Windows "Copy as path" arrives
     * quoted, and {@code "C:\s\session"} + {@code .gm} would otherwise become
     * {@code "C:\s\session".gm}, an extension the check below cannot even see.
     */
    private File stateFile(String path) {
        String normalized = UserPath.normalize(path);
        return new File(normalized.endsWith(STATE_EXTENSION) ? normalized : normalized + STATE_EXTENSION);
    }

    /**
     * Whether a file has been loaded successfully.
     *
     * <p>Lets the UI turn a command away before it prompts for anything, rather than
     * collecting an event id and a share count only to fail on the engine call. The
     * commands below still check for themselves; they can't trust a caller to have asked.
     */
    public boolean isFileLoaded() {
        return fileLoaded;
    }

    /**
     * Acts as {@code name} from here on. This is an admin console: anyone in the file may
     * be selected, and the only thing the choice restricts is who may close an event.
     */
    public UserView selectUser(String name) {
        requireFileLoaded();
        User user = eventManager.getUser(name);
        currentUserName = user.getName();
        return buildUserView(user);
    }

    /** The name being acted as, or {@code null} if none has been chosen yet. */
    public String getCurrentUserName() {
        return currentUserName;
    }

    /** Every user in the file, for the UI to offer as a choice. */
    public List<UserView> getUsers() {
        requireFileLoaded();
        return eventManager.getAllUsers().stream().map(this::buildUserView).toList();
    }

    /** The selected user's account: their money and everything they hold. */
    public UserView getAccount() {
        requireFileLoaded();
        return buildUserView(requireSelectedUser());
    }

    public List<EventView> getEvents() {
        requireFileLoaded();
        return eventManager.getAllEvents().stream().map(this::viewOf).toList();
    }

    /** One event as the UI sees it, the same way whether it was listed or just created. */
    private EventView viewOf(Event event) {
        return new EventView(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getCommissionRate(),
                event.getCommissionMethod().name(),
                event.isLmsr() ? "LMSR" : "ORDER_BOOK",
                marketMakerName(event.getId()),
                List.of(event.getOption(0).getName(), event.getOption(1).getName()),
                event.getStatus().name());
    }

    public EventStatusView getEventStatus(int eventId) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);
        requireLmsrMarket(event);
        return buildStatusView(event);
    }

    /**
     * What both options were priced at when the event opened and after each purchase since,
     * oldest first.
     *
     * <p>An LMSR price is a function of how many shares exist rather than of anything the
     * trade log records, so the series cannot be read off the history the way an order
     * book's can: it has to be replayed through {@link LmsrCalculator}, and that belongs
     * on this side of the facade rather than in a UI.
     *
     * <p>The replay starts at no shares at all, which is a real price and the one the market
     * opened at (0.5 on each side of a binary event, whatever {@code b} is), so the series
     * begins there rather than at the first transaction. An event nobody has traded has a
     * price, and a chart of one should say so instead of being empty.
     *
     * @return {@code {priceOfOption0, priceOfOption1}} at the open, then one pair per trade;
     *         never empty for an event that exists
     */
    public List<double[]> getPriceHistory(int eventId) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);
        requireLmsrMarket(event);

        List<Trade> oldestFirst = new ArrayList<>(event.getTrades());
        String firstOption = event.getOption(0).getName();
        double b = event.getB();

        List<double[]> series = new ArrayList<>();
        long q0 = 0;
        long q1 = 0;
        series.add(lmsr.prices(q0, q1, b));     // the open: nothing bought, nothing implied
        for (Trade trade : oldestFirst) {
            if (trade.optionName().equals(firstOption)) {
                q0 += trade.shares();
            } else {
                q1 += trade.shares();
            }
            series.add(lmsr.prices(q0, q1, b));
        }
        return series;
    }

    /**
     * The live state of an order-book event: both books, their price indicators and the
     * history. The LMSR counterpart is {@link #getEventStatus}; the UI picks by the
     * {@code tradingMethod} it read from {@link #getEvents()}.
     */
    public OrderBookStatusView getOrderBookStatus(int eventId) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);
        requireOrderBookMarket(event);
        return buildOrderBookView(event);
    }

    /**
     * Places an order in an event's book on behalf of the selected user.
     *
     * <p>What comes back says what happened immediately (shares bought from a resting
     * seller, or minted with a buyer of the other option) and how much of the order is now
     * waiting for someone to take the other side.
     */
    public OrderResult placeOrder(int eventId, int optionIndex, OrderSide side, double price, long quantity) {
        requireFileLoaded();
        User trader = requireSelectedUser();
        Event event = eventManager.getEvent(eventId);
        requireOrderBookMarket(event);

        OrderOutcome outcome = executor.submit(eventManager, event, trader, optionIndex, side, price, quantity);
        Order order = outcome.order();

        List<FillView> fills = new ArrayList<>();
        for (BookTrade fill : outcome.fills()) {
            fills.add(new FillView(
                    fill.kind().name(),
                    fill.optionName(),
                    fill.price(),
                    fill.quantity(),
                    side == OrderSide.BUY ? fill.seller() : fill.buyer(),
                    fill.amount(),
                    fill.commission()));
        }

        return new OrderResult(
                order.getSequence(),
                order.getSide().name(),
                event.getOption(order.getOptionIndex()).getName(),
                order.getPrice(),
                order.getQuantity(),
                order.getFilled(),
                order.getRemaining(),
                fills);
    }

    /**
     * What buying {@code shares} of one outcome would cost, without buying anything.
     *
     * <p>Nothing here is validated beyond the shape of the request: it is a price tag, not
     * a purchase, and whether the selected user can afford it is {@link #participate}'s
     * question at the moment they commit. Nothing is mutated either: the figures are the
     * same ones {@code participate} would compute, taken from the same calculator.
     *
     * @throws InvalidShareAmountException if {@code shares} is not a positive whole number
     */
    public PurchaseQuote quoteParticipation(int eventId, int optionIndex, long shares) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);
        requireLmsrMarket(event);
        validateOptionIndex(optionIndex);
        if (shares <= 0) {
            throw new InvalidShareAmountException("Number of shares must be a positive whole number.");
        }

        long q0 = event.getOption(0).getShares();
        long q1 = event.getOption(1).getShares();
        long alreadyIssued = optionIndex == 0 ? q0 : q1;
        if (shares > Long.MAX_VALUE - alreadyIssued) {
            throw new InvalidShareAmountException("That many shares would overflow the count for '"
                    + event.getOption(optionIndex).getName() + "' (" + alreadyIssued + " already issued).");
        }

        double b = event.getB();
        double sharesCost = lmsr.purchaseCost(q0, q1, optionIndex, shares, b);
        double commission = event.getCommissionMethod() == CommissionMethod.PER_PURCHASE
                ? sharesCost * event.getCommissionRate()
                : 0.0;
        double[] after = optionIndex == 0
                ? lmsr.prices(q0 + shares, q1, b)
                : lmsr.prices(q0, q1 + shares, b);

        return new PurchaseQuote(event.getOption(optionIndex).getName(), shares,
                sharesCost, commission, sharesCost + commission, after[optionIndex]);
    }

    /**
     * Buys {@code shares} of one outcome at the LMSR price.
     *
     * <p>The selected user pays for it: cost plus commission leaves their balance, and the
     * shares land in their holdings for the event.
     *
     * <p>Everything is validated and every figure computed before the first mutation,
     * so a rejected purchase can't leave shares issued against an untouched account.
     */
    public PurchaseResult participate(int eventId, int optionIndex, long shares) {
        requireFileLoaded();
        User buyer = requireSelectedUser();
        Event event = eventManager.getEvent(eventId);
        requireLmsrMarket(event);

        if (!event.isActive()) {
            throw new EventClosedException("Event " + eventId + " is already closed; no more purchases.");
        }
        validateOptionIndex(optionIndex);
        if (shares <= 0) {
            throw new InvalidShareAmountException("Number of shares must be a positive whole number.");
        }

        long q0 = event.getOption(0).getShares();
        long q1 = event.getOption(1).getShares();

        // The LMSR itself is exact at any share count, but the outstanding count is a
        // long: without this the total would wrap negative, and a negative q is a state
        // the b·ln2 solvency proof says nothing about.
        long alreadyIssued = optionIndex == 0 ? q0 : q1;
        if (shares > Long.MAX_VALUE - alreadyIssued) {
            throw new InvalidShareAmountException("That many shares would overflow the count for '"
                    + event.getOption(optionIndex).getName() + "' (" + alreadyIssued + " already issued).");
        }

        double sharesCost = lmsr.purchaseCost(q0, q1, optionIndex, shares, event.getB());
        double commission = event.getCommissionMethod() == CommissionMethod.PER_PURCHASE
                ? sharesCost * event.getCommissionRate()
                : 0.0;
        double totalPaid = sharesCost + commission;

        if (buyer.getAvailableCash() < totalPaid) {
            throw new InsufficientFundsException(String.format(
                    "%s cannot afford that: it costs %.2f and only %.2f is available.",
                    buyer.getName(), totalPaid, buyer.getAvailableCash()));
        }

        // --- commit ---
        Option bought = event.getOption(optionIndex);
        bought.addShares(shares);
        Account account = event.getMMAccount();
        account.deposit(sharesCost);
        if (commission > 0) {
            payCommission(event, eventManager.requireMarketMaker(eventId), commission);
        }
        buyer.withdraw(totalPaid);
        buyer.addShares(eventId, optionIndex, shares);
        event.recordTrade(bought.getName(), shares, sharesCost, commission);

        return new PurchaseResult(bought.getName(), shares, sharesCost, commission, totalPaid,
                buildStatusView(event));
    }

    /**
     * Settles an event. Winners are owed one currency unit per share; under
     * {@code ON_CLOSE} the operator's commission is deducted from that payout, so only
     * winning participants ever pay it.
     *
     * <p>Funding the commission out of the winnings rather than out of the account is
     * what makes it always affordable: the account still parts with exactly the gross
     * obligation it was subsidised for, so the b·ln2 solvency guarantee is untouched no
     * matter how one-sided the market got.
     */
    public SettlementResult closeEvent(int eventId, int winningOptionIndex) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);
        requireMarketMaker(event);

        if (!event.isActive()) {
            throw new EventClosedException("Event " + eventId + " has already been closed.");
        }
        validateOptionIndex(winningOptionIndex);

        return event.isLmsr()
                ? closeLmsrEvent(event, winningOptionIndex)
                : closeOrderBookEvent(event, winningOptionIndex);
    }

    /**
     * Settles an LMSR event out of its subsidised account.
     *
     * <p>The account is left empty. What the winners are owed goes to them, the commission
     * goes to the Market Maker, and whatever the scoring rule did not spend (the unused
     * part of the {@code b·ln2} subsidy, plus everything buyers paid in) goes back to the
     * Market Maker too: they put the subsidy up, so the remainder is theirs to take back.
     *
     * <p>The subsidy itself came from the house rather than from them, so this is the one
     * settlement that hands out money nobody put in: the same b·ln2 the conservation
     * identity already makes an exception for, arriving where it can be seen.
     */
    private SettlementResult closeLmsrEvent(Event event, int winningOptionIndex) {
        int eventId = event.getId();
        Account account = event.getMMAccount();
        User marketMaker = eventManager.requireMarketMaker(eventId);
        long[] sharesPerOption = {
                event.getOption(0).getShares(),
                event.getOption(1).getShares()
        };

        // The full obligation, one unit per winning share, leaves the account either way.
        double grossWinnings = sharesPerOption[winningOptionIndex] * LMSR_SHARE_VALUE;
        account.withdraw(grossWinnings);

        // Under ON_CLOSE the operator's share is carved out of that, leaving the rest
        // for the winners. No cap is needed: the money is already owed to someone.
        double commissionRate = event.getCommissionMethod() == CommissionMethod.ON_CLOSE
                ? event.getCommissionRate()
                : 0.0;
        double commissionMoved = grossWinnings * commissionRate;
        if (commissionMoved > 0) {
            payCommission(event, marketMaker, commissionMoved);
        }
        double totalPaidToWinners = grossWinnings - commissionMoved;

        // The same sum, split among the people who actually hold the winning shares. It
        // adds up to totalPaidToWinners because every share was bought by one of them.
        for (User holder : eventManager.getAllUsers()) {
            long held = holder.getShares(eventId, winningOptionIndex);
            if (held > 0) {
                double gross = held * LMSR_SHARE_VALUE;
                holder.deposit(gross - gross * commissionRate);
            }
        }

        // Whatever the payout did not need. Never negative, which is what b·ln2 buys, but
        // taken only when it is genuinely positive, so rounding can't hand anyone a debt.
        double subsidyReturned = 0.0;
        if (account.getBalance() > 0) {
            subsidyReturned = account.getBalance();
            account.withdraw(subsidyReturned);
            marketMaker.deposit(subsidyReturned);
        }

        event.close(winningOptionIndex);

        return new SettlementResult(eventId,
                event.getOption(winningOptionIndex).getName(),
                sharesPerOption,
                commissionMoved,
                totalPaidToWinners,
                subsidyReturned);
    }

    /**
     * Settles an order-book event.
     *
     * <p>The book closes to new orders first, and everything still waiting in it is
     * released (the cash behind resting buys goes back to being spendable, the shares
     * behind resting sells back to being sellable) because none of it will ever trade now.
     *
     * <p>Then every holder of the winning option is paid the base value per share out of
     * the event's account, which has been collecting exactly that much per pair ever
     * created. Losing shares pay nothing. Under {@code ON_CLOSE} the commission comes out of
     * the winners' money on its way past, so the account still parts with exactly what it
     * was funded for.
     */
    private SettlementResult closeOrderBookEvent(Event event, int winningOptionIndex) {
        int eventId = event.getId();
        OrderBook book = event.getOrderBook();

        for (Order order : book.restingOrders()) {
            User owner = eventManager.getUser(order.getUserName());
            if (order.getSide() == OrderSide.BUY) {
                owner.release(OrderExecutor.reservationFor(event, order));
            } else {
                owner.unlockShares(eventId, order.getOptionIndex(), order.getRemaining());
            }
        }
        book.clear();

        double baseValue = book.getD();
        double commissionRate = event.getCommissionMethod() == CommissionMethod.ON_CLOSE
                ? event.getCommissionRate()
                : 0.0;

        double grossWinnings = 0.0;
        double commissionMoved = 0.0;
        for (User holder : eventManager.getAllUsers()) {
            long held = holder.getShares(eventId, winningOptionIndex);
            if (held == 0) {
                continue;
            }
            double gross = held * baseValue;
            double commission = gross * commissionRate;
            holder.deposit(gross - commission);
            grossWinnings += gross;
            commissionMoved += commission;
        }

        Account account = event.getMMAccount();
        account.withdraw(grossWinnings);
        if (commissionMoved > 0) {
            payCommission(event, eventManager.requireMarketMaker(eventId), commissionMoved);
        }

        event.close(winningOptionIndex);

        // Nothing to give back: the account took in exactly d per pair ever created and has
        // just paid out exactly d per winning share, and there is one winning share a pair.
        return new SettlementResult(eventId,
                event.getOption(winningOptionIndex).getName(),
                new long[] { event.getOption(0).getShares(), event.getOption(1).getShares() },
                commissionMoved,
                grossWinnings - commissionMoved,
                0.0);
    }

    // --- internals/private methods ---

    /**
     * Books the operator's cut: recorded on the event, and paid into the Market Maker's
     * balance. The same two lines as {@code OrderExecutor.payCommission}, for the trades
     * that don't go through a book.
     *
     * <p>The two pots are separate on purpose: {@code addCommission} never touches the
     * account's balance, which exists to cover the payout and nothing else, so this is the
     * record and the deposit is the money.
     *
     * @param marketMaker the event's maker, from {@code EventManager.requireMarketMaker}
     */
    private void payCommission(Event event, User marketMaker, double commission) {
        event.getMMAccount().addCommission(commission);
        marketMaker.deposit(commission);
    }

    private EventStatusView buildStatusView(Event event) {
        long q0 = event.getOption(0).getShares();
        long q1 = event.getOption(1).getShares();
        double[] prices = lmsr.prices(q0, q1, event.getB());

        List<OptionView> options = List.of(
                new OptionView(event.getOption(0).getName(), prices[0], q0),
                new OptionView(event.getOption(1).getName(), prices[1], q1));

        List<Trade> history = new ArrayList<>(event.getTrades());
        Collections.reverse(history);   // newest first

        boolean closed = !event.isActive();
        Integer winner = event.getWinningOptionIndex();

        return new EventStatusView(
                event.getId(),
                event.getName(),
                options,
                event.getMMAccount().getBalance(),
                event.getMMAccount().getCommissionCollected(),
                event.getB(),
                List.copyOf(history),
                closed,
                winner == null ? null : event.getOption(winner).getName(),
                closed ? new long[] { q0, q1 } : null);
    }

    private OrderBookStatusView buildOrderBookView(Event event) {
        OrderBook book = event.getOrderBook();

        List<OptionBookView> options = new ArrayList<>();
        for (int i = 0; i < Event.OPTION_COUNT; i++) {
            options.add(new OptionBookView(
                    event.getOption(i).getName(),
                    event.getOption(i).getShares(),
                    book.getLastPrice(i),
                    book.getBestBid(i),
                    book.getBestAsk(i),
                    book.getMidPrice(i),
                    book.getSpread(i),
                    orderLines(book.getBids(i)),
                    orderLines(book.getAsks(i))));
        }

        List<BookTrade> history = new ArrayList<>(book.getHistory());
        Collections.reverse(history);   // newest first
        Integer winner = event.getWinningOptionIndex();

        // Only an event that was given an initial investment opened at a price: its maker
        // paid d for every pair, which values each side at d/2. One that was not opened with
        // nobody holding anything, and its chart has nothing to start from.
        Double openingPrice = event.getTradingMethod() instanceof TradingMethod.OrderBook settings
                && settings.initialInvestment() > 0 ? settings.openingPrice() : null;

        return new OrderBookStatusView(
                event.getId(),
                event.getName(),
                book.getD(),
                openingPrice,
                book.allowsMint(),
                marketMakerName(event.getId()),
                event.getMMAccount().getBalance(),
                event.getMMAccount().getCommissionCollected(),
                options,
                List.copyOf(history),
                !event.isActive(),
                winner == null ? null : event.getOption(winner).getName());
    }

    private static List<OrderLineView> orderLines(List<Order> orders) {
        return orders.stream()
                .map(order -> new OrderLineView(order.getSequence(), order.getUserName(),
                        order.getPrice(), order.getRemaining()))
                .toList();
    }

    /**
     * Turns away the commands that only mean something for a scoring rule: an LMSR price,
     * and a purchase at that price. The order-book event has its own pair of commands, and
     * saying so is better than pricing it with a b it does not have.
     */
    private void requireLmsrMarket(Event event) {
        if (!event.isLmsr()) {
            throw new UnsupportedMethodException("Event " + event.getId() + " ('" + event.getName()
                    + "') trades on an order book; place an order instead.");
        }
    }

    /** The mirror image, for the commands that only an order book can answer. */
    private void requireOrderBookMarket(Event event) {
        if (!event.isOrderBook()) {
            throw new UnsupportedMethodException("Event " + event.getId() + " ('" + event.getName()
                    + "') is an LMSR market and has no order book. Participate in it instead.");
        }
    }

    /**
     * The user the console is acting as.
     *
     * @throws NoUserSelectedException if none has been chosen; every command that moves
     *                                 money belongs to somebody
     */
    private User requireSelectedUser() {
        if (currentUserName == null) {
            throw new NoUserSelectedException("Select a user first (command 2).");
        }
        return eventManager.getUser(currentUserName);
    }

    /**
     * Only an event's Market Maker may seal it: Appendix B gives them the job of declaring
     * the winning option, and they are the one whose money is at stake in it.
     *
     * <p>This applies to every event and both trading methods, because every event has a
     * maker: the user the file names, or the one who created it here.
     */
    private void requireMarketMaker(Event event) {
        User marketMaker = eventManager.requireMarketMaker(event.getId());
        User user = requireSelectedUser();
        if (!user.getName().equals(marketMaker.getName())) {
            throw new NotMarketMakerException("Only '" + marketMaker.getName()
                    + "', the market maker for event " + event.getId() + ", can close it.");
        }
    }

    private String marketMakerName(int eventId) {
        User marketMaker = eventManager.getMarketMaker(eventId);
        return marketMaker == null ? null : marketMaker.getName();
    }

    private UserView buildUserView(User user) {
        List<HoldingView> holdings = user.getEventIds().stream()
                .map(eventId -> holdingOf(user, eventManager.getEvent(eventId)))
                .toList();
        return new UserView(
                user.getName(),
                user.getInitialCash(),
                user.getBalance(),
                user.getReservedCash(),
                user.getAvailableCash(),
                user.getMarketMakerEventIds(),
                holdings);
    }

    /** What one user holds in one event, both options at once, a row of their account. */
    private static HoldingView holdingOf(User user, Event event) {
        int eventId = event.getId();
        return new HoldingView(
                eventId,
                event.getName(),
                List.of(event.getOption(0).getName(), event.getOption(1).getName()),
                new long[] { user.getShares(eventId, 0), user.getShares(eventId, 1) },
                new long[] { user.getLockedShares(eventId, 0), user.getLockedShares(eventId, 1) });
    }

    private void requireFileLoaded() {
        if (!fileLoaded) {
            throw new NoFileLoadedException("Load an events file first (command 1).");
        }
    }

    private void validateOptionIndex(int optionIndex) {
        if (optionIndex < 0 || optionIndex >= Event.OPTION_COUNT) {
            throw new InvalidOptionException("Option must be 1 or " + Event.OPTION_COUNT + ".");
        }
    }
}
