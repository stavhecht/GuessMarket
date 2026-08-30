package engine.service;

import engine.exception.EventNotFoundException;
import engine.exception.InvalidEventException;
import engine.exception.UserNotFoundException;
import engine.model.Event;
import engine.model.OrderSide;
import engine.model.TradingMethod;
import engine.model.User;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the loaded events and users. A new file replaces everything that came before.
 *
 * <p>This is the whole of the system's mutable state — the events with their books and
 * accounts, and the users with their money and holdings — which is why saving and loading
 * a session is a matter of writing this one object out and reading it back.
 */
public class EventManager implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // LinkedHashMap so the UI lists events and users in file order rather than hash order.
    private final Map<Integer, Event> events = new LinkedHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();

    /**
     * Replaces the current events and users wholesale — all market state from the previous
     * file is dropped.
     *
     * <p>The two arrive together because they only make sense together: the loader has
     * already checked that every user's market-maker id names an event in the same list.
     */
    public void load(List<Event> newEvents, List<User> newUsers) {
        events.clear();
        users.clear();
        for (Event event : newEvents) {
            events.put(event.getId(), event);
        }
        for (User user : newUsers) {
            users.put(user.getName(), user);
        }
    }

    /**
     * Adds one event to the market a file already loaded, for
     * {@code MarketEngine.createLmsrEvent} and its order-book twin.
     *
     * <p>The id is checked here rather than trusted: {@link #load} may put an event straight
     * into the map because the loader has already refused a file with a duplicate id, but
     * nothing has vetted an event built at runtime, and a collision would silently replace a
     * live market — its account, its holdings and its whole trade history — with an empty one.
     */
    public void addEvent(Event event) {
        if (event.getId() <= 0) {
            throw new InvalidEventException("An event's id must be greater than 0.");
        }
        if (events.containsKey(event.getId())) {
            throw new InvalidEventException("There is already an event with id " + event.getId() + ".");
        }
        events.put(event.getId(), event);
    }

    /** The next free id: one past the highest in use, so it can never collide. */
    public int nextEventId() {
        int highest = 0;
        for (int id : events.keySet()) {
            highest = Math.max(highest, id);
        }
        return highest + 1;
    }

    public Event getEvent(int id) {
        Event event = events.get(id);
        if (event == null) {
            throw new EventNotFoundException("There is no event with id " + id + ".");
        }
        return event;
    }

    public Collection<Event> getAllEvents() {
        return Collections.unmodifiableCollection(events.values());
    }

    public User getUser(String name) {
        User user = users.get(name);
        if (user == null) {
            throw new UserNotFoundException("There is no user named '" + name + "'.");
        }
        return user;
    }

    public Collection<User> getAllUsers() {
        return Collections.unmodifiableCollection(users.values());
    }

    /**
     * The user who funds and settles this event, or {@code null} if the file named none.
     *
     * <p>Looked up rather than stored on the event: the file states it the other way round,
     * on the user, and the loader has already made sure no two users claim the same event.
     */
    public User getMarketMaker(int eventId) {
        for (User user : users.values()) {
            if (user.isMarketMakerOf(eventId)) {
                return user;
            }
        }
        return null;
    }

    /**
     * Gives every order-book event its opening shares. Call once, right after loading.
     *
     * <p>Appendix B: the Market Maker funds the event out of pocket and offers the shares
     * they get for it to the market. So the investment leaves their balance for the event's
     * account, they receive one pair per base value paid — {@code inital / d} of each option
     * — and both halves are immediately offered for sale at {@code d/2}, the price at which
     * the two options are worth the same.
     *
     * <p>The offers go through {@link OrderExecutor} rather than onto the book directly, so
     * the shares are locked the way any other seller's would be. Nothing can match them yet:
     * this runs before anyone has had a chance to place an order.
     */
    public void applyInitialAllocations(OrderExecutor executor) {
        for (Event event : events.values()) {
            User marketMaker = getMarketMaker(event.getId());
            if (marketMaker != null) {
                allocateInitial(event, marketMaker, executor);
            }
        }
    }

    /**
     * Opens ONE order-book event: the Market Maker buys the first pairs of shares and offers
     * them back to the market at half the base value a side.
     *
     * <p>Split out of {@link #applyInitialAllocations} so an event created at runtime can be
     * opened without re-opening every other one. <b>Do not reach for the all-events form to
     * do that</b> — it is not idempotent, and running it twice would have every Market Maker
     * fund their event a second time.
     *
     * <p>Does nothing for an LMSR event, or for an order book the file gave nothing to open
     * with: that book opens empty, which the engine allows and the price chart reports by
     * having no opening price.
     *
     * @param marketMaker the event's maker, already checked to be able to afford the
     *                    investment — this method moves the money, it does not vet it
     */
    public void allocateInitial(Event event, User marketMaker, OrderExecutor executor) {
        if (!(event.getTradingMethod() instanceof TradingMethod.OrderBook settings)
                || settings.initialInvestment() == 0) {
            return;
        }
        long pairs = settings.initialInvestment() / settings.d();
        marketMaker.withdraw(settings.initialInvestment());
        event.getMMAccount().deposit(settings.initialInvestment());
        for (int optionIndex = 0; optionIndex < Event.OPTION_COUNT; optionIndex++) {
            event.getOption(optionIndex).addShares(pairs);
            marketMaker.addShares(event.getId(), optionIndex, pairs);
        }

        double openingPrice = settings.openingPrice();
        for (int optionIndex = 0; optionIndex < Event.OPTION_COUNT; optionIndex++) {
            executor.submit(this, event, marketMaker, optionIndex, OrderSide.SELL, openingPrice, pairs);
        }
    }

    /** Seeds every LMSR event's account with b·ln2 so payouts are always covered. Call once, right after loading. */
    public void applyInitialSubsidies(LmsrCalculator calculator) {
        for (Event event : events.values()) {
            subsidise(event, calculator);
        }
    }

    /**
     * Seeds ONE LMSR event's account with its provable worst-case loss, {@code b·ln2}.
     *
     * <p>Split out for the same reason as {@link #allocateInitial}: an event created at
     * runtime needs its own subsidy and nobody else's. The all-events form would deposit a
     * second {@code b·ln2} into every LMSR event already open, which is money the house
     * never put in — the conservation identity would stop holding, and the failure would
     * show up nowhere near the cause.
     *
     * <p>Does nothing for an order-book event, which is solvent by a different argument.
     */
    public void subsidise(Event event, LmsrCalculator calculator) {
        if (event.isLmsr()) {
            event.getMMAccount().deposit(calculator.initialSubsidy(event.getB()));
        }
    }
}
