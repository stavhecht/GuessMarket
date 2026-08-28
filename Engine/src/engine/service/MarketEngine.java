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
import engine.model.User;

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
 * The front — the only engine class the UI imports.
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

    /** Not final: loading a saved session replaces the whole collection at once. */
    private EventManager eventManager = new EventManager();
    private final LmsrCalculator lmsr = new LmsrCalculator();
    private final XmlEventLoader loader = new XmlEventLoader();
    private final OrderExecutor executor = new OrderExecutor();
    private boolean fileLoaded =  false;

    /**
     * Who the console is acting as. Held by name rather than by reference so that loading
     * a session cannot leave it pointing at a user from the file before it, and
     * deliberately not part of the saved state — a restored session asks who you are again.
     */
    private String currentUserName;

    /**
     * Loads a new events file, replacing everything currently in memory.
     * If the file is rejected, the previously loaded state is left untouched.
     */
    public void loadEventsFile(String path) {
        LoadedMarket market = loader.load(path);
        eventManager.load(market.events(), market.users());
        eventManager.applyInitialSubsidies(lmsr);
        eventManager.applyInitialAllocations(executor);
        currentUserName = null;   // the new file has its own users
        fileLoaded = true;
    }

    /**
     * Writes the current session — every event with its prices, balances and trade
     * history — to {@code path} plus {@value #STATE_EXTENSION}.
     *
     * <p>{@link EventManager} holds all of it and is {@link java.io.Serializable}, so one
     * {@code writeObject} captures the lot. The user supplies the path without an
     * extension, as the brief asks; typing one anyway is tolerated rather than doubled.
     *
     * @return the path of the file actually written, for the UI to report
     */
    public String saveState(String path) {
        File file = stateFile(path);
        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(file))) {
            stream.writeObject(eventManager);
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
     * @return the path of the file actually read, for the UI to report
     */
    public String loadState(String path) {
        File file = stateFile(path);
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(file))) {
            eventManager = (EventManager) stream.readObject();
            currentUserName = null;   // the restored session has its own users
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
     * The path is normalised first ({@link UserPath}) — a Windows "Copy as path" arrives
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
     * commands below still check for themselves — they can't trust a caller to have asked.
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
        List<UserView> views = new ArrayList<>();
        for (User user : eventManager.getAllUsers()) {
            views.add(buildUserView(user));
        }
        return views;
    }

    /** The selected user's account: their money and everything they hold. */
    public UserView getAccount() {
        requireFileLoaded();
        return buildUserView(requireSelectedUser());
    }

    public List<EventView> getEvents() {
        requireFileLoaded();
        List<EventView> views = new ArrayList<>();
        for (Event event : eventManager.getAllEvents()) {
            views.add(new EventView(
                    event.getId(),
                    event.getName(),
                    event.getDescription(),
                    event.getCommissionRate(),
                    event.getCommissionMethod().name(),
                    event.isLmsr() ? "LMSR" : "ORDER_BOOK",
                    marketMakerName(event.getId()),
                    List.of(event.getOption(0).getName(), event.getOption(1).getName()),
                    event.getStatus().name()));
        }
        return views;
    }

    public EventStatusView getEventStatus(int eventId) {
        requireFileLoaded();
        Event event = eventManager.getEvent(eventId);
        requireLmsrMarket(event);
        return buildStatusView(event);
    }

    /**
     * What both options were priced at after each purchase in an LMSR event, oldest first.
     *
     * <p>An LMSR price is a function of how many shares exist rather than of anything the
     * trade log records, so the series cannot be read off the history the way an order
     * book's can — it has to be replayed through {@link LmsrCalculator}, and that belongs
     * on this side of the facade rather than in a UI.
     *
     * @return one {@code {priceOfOption0, priceOfOption1}} pair per trade; empty if nothing
     *         has been bought yet
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
     * <p>What comes back says what happened immediately — shares bought from a resting
     * seller, or minted with a buyer of the other option — and how much of the order is now
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
                fills,
                buildOrderBookView(event));
    }

    /**
     * What buying {@code shares} of one outcome would cost, without buying anything.
     *
     * <p>Nothing here is validated beyond the shape of the request: it is a price tag, not
     * a purchase, and whether the selected user can afford it is {@link #participate}'s
     * question at the moment they commit. Nothing is mutated either — the figures are the
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
            throw new EventClosedException("Event " + eventId + " is already closed — no more purchases.");
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
            account.addCommission(commission);
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

    /** Settles an LMSR event out of its subsidised account. */
    private SettlementResult closeLmsrEvent(Event event, int winningOptionIndex) {
        int eventId = event.getId();
        Account account = event.getMMAccount();
        long[] sharesPerOption = {
                event.getOption(0).getShares(),
                event.getOption(1).getShares()
        };

        // The full obligation — one unit per winning share — leaves the account either way.
        double grossWinnings = sharesPerOption[winningOptionIndex] * LMSR_SHARE_VALUE;
        account.withdraw(grossWinnings);

        // Under ON_CLOSE the operator's share is carved out of that, leaving the rest
        // for the winners. No cap is needed: the money is already owed to someone.
        double commissionRate = event.getCommissionMethod() == CommissionMethod.ON_CLOSE
                ? event.getCommissionRate()
                : 0.0;
        double commissionMoved = grossWinnings * commissionRate;
        if (commissionMoved > 0) {
            account.addCommission(commissionMoved);
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

        event.close(winningOptionIndex);

        return new SettlementResult(eventId,
                event.getOption(winningOptionIndex).getName(),
                sharesPerOption,
                commissionMoved,
                totalPaidToWinners);
    }

    /**
     * Settles an order-book event.
     *
     * <p>The book closes to new orders first, and everything still waiting in it is
     * released — the cash behind resting buys goes back to being spendable, the shares
     * behind resting sells back to being sellable — because none of it will ever trade now.
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
            account.addCommission(commissionMoved);
            User marketMaker = eventManager.getMarketMaker(eventId);
            if (marketMaker != null) {
                marketMaker.deposit(commissionMoved);
            }
        }

        event.close(winningOptionIndex);

        return new SettlementResult(eventId,
                event.getOption(winningOptionIndex).getName(),
                new long[] { event.getOption(0).getShares(), event.getOption(1).getShares() },
                commissionMoved,
                grossWinnings - commissionMoved);
    }

    // --- internals/private methods ---

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

        return new OrderBookStatusView(
                event.getId(),
                event.getName(),
                book.getD(),
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
        List<OrderLineView> lines = new ArrayList<>();
        for (Order order : orders) {
            lines.add(new OrderLineView(order.getSequence(), order.getUserName(),
                    order.getPrice(), order.getRemaining()));
        }
        return lines;
    }

    /**
     * Turns away the commands that only mean something for a scoring rule: an LMSR price,
     * and a purchase at that price. The order-book event has its own pair of commands, and
     * saying so is better than pricing it with a b it does not have.
     */
    private void requireLmsrMarket(Event event) {
        if (!event.isLmsr()) {
            throw new UnsupportedMethodException("Event " + event.getId() + " ('" + event.getName()
                    + "') trades on an order book — place an order instead.");
        }
    }

    /** The mirror image, for the commands that only an order book can answer. */
    private void requireOrderBookMarket(Event event) {
        if (!event.isOrderBook()) {
            throw new UnsupportedMethodException("Event " + event.getId() + " ('" + event.getName()
                    + "') is an LMSR market — it has no order book. Participate in it instead.");
        }
    }

    /**
     * The user the console is acting as.
     *
     * @throws NoUserSelectedException if none has been chosen — every command that moves
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
     * <p>An event the file gave no Market Maker is open to whoever is selected — there is
     * nobody to restrict it to.
     */
    private void requireMarketMaker(Event event) {
        User marketMaker = eventManager.getMarketMaker(event.getId());
        if (marketMaker == null) {
            return;
        }
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
        List<HoldingView> holdings = new ArrayList<>();
        for (int eventId : user.getEventIds()) {
            Event event = eventManager.getEvent(eventId);
            holdings.add(new HoldingView(
                    eventId,
                    event.getName(),
                    List.of(event.getOption(0).getName(), event.getOption(1).getName()),
                    new long[] { user.getShares(eventId, 0), user.getShares(eventId, 1) },
                    new long[] { user.getLockedShares(eventId, 0), user.getLockedShares(eventId, 1) }));
        }
        return new UserView(
                user.getName(),
                user.getBalance(),
                user.getReservedCash(),
                user.getAvailableCash(),
                user.getMarketMakerEventIds(),
                holdings);
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
