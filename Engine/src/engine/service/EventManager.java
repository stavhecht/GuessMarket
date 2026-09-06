package engine.service;

import engine.exception.EventNotFoundException;
import engine.exception.InvalidEventException;
import engine.exception.UserNotFoundException;
import engine.model.Event;
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
 * <p>This is the whole of the system's mutable state (the events with their books and
 * accounts, and the users with their money and holdings), which is why saving and loading
 * a session is a matter of writing this one object out and reading it back.
 */
public class EventManager implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // LinkedHashMap so the UI lists events and users in file order rather than hash order.
    private final Map<Integer, Event> events = new LinkedHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();

    /**
     * Replaces the current events and users wholesale: all market state from the previous
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
     * live market, its account and holdings and whole trade history included, with an empty one.
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
        return events.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
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
     * The user who funds and settles this event, or {@code null} if nobody claims it.
     *
     * <p>Looked up rather than stored on the event: the file states it the other way round,
     * on the user, and the loader has already made sure no two users claim the same event.
     *
     * <p>Every event in a validly loaded market has one, so a caller that needs the maker
     * rather than merely reporting whether there is one should use
     * {@link #requireMarketMaker(int)} instead of testing this for null.
     */
    public User getMarketMaker(int eventId) {
        return users.values().stream()
                .filter(user -> user.isMarketMakerOf(eventId))
                .findFirst()
                .orElse(null);
    }

    /**
     * The event's Market Maker, which every event has: the XML loader refuses a file that
     * leaves one unclaimed, {@code MarketEngine} makes an event's creator its maker, and
     * {@link #requireEveryEventHasAMarketMaker()} re-checks the whole market when a saved
     * session is restored.
     *
     * <p>So this throwing where {@link #getMarketMaker} would have returned null means the
     * invariant has been broken somewhere upstream, not that the market is unusual.
     */
    public User requireMarketMaker(int eventId) {
        User marketMaker = getMarketMaker(eventId);
        if (marketMaker == null) {
            throw new InvalidEventException("Event " + eventId + " has no market maker.");
        }
        return marketMaker;
    }

    /**
     * Checks that invariant across the whole market, for state that did not come through
     * the loader: a restored {@code .gm} session, which is deserialised rather than built.
     *
     * @throws InvalidEventException naming the first event nobody runs
     */
    public void requireEveryEventHasAMarketMaker() {
        for (Event event : events.values()) {
            if (getMarketMaker(event.getId()) == null) {
                throw new InvalidEventException("Event " + event.getId() + " ('" + event.getName()
                        + "') has no market maker. Every event must be run by exactly one user.");
            }
        }
    }

    /**
     * Gives every order-book event its opening shares. Call once, right after loading.
     *
     * <p>Appendix B: the Market Maker funds the event out of pocket and receives the shares
     * they paid for. So the investment leaves their balance for the event's account and they
     * get one pair per base value paid, {@code inital / d} of each option.
     *
     * <p>Those shares are theirs to <em>hold</em>: nothing is offered for sale here. The
     * Market Maker decides when and at what price to sell, like any other participant, so the
     * book opens empty and the first quote is somebody's deliberate choice rather than an
     * automatic one at {@code d/2}.
     */
    public void applyInitialAllocations() {
        for (Event event : events.values()) {
            User marketMaker = getMarketMaker(event.getId());
            if (marketMaker != null) {
                allocateInitial(event, marketMaker);
            }
        }
    }

    /**
     * Opens ONE order-book event: the Market Maker buys the first pairs of shares and keeps
     * them.
     *
     * <p>Split out of {@link #applyInitialAllocations} so an event created at runtime can be
     * opened without re-opening every other one. <b>Do not reach for the all-events form to
     * do that</b>: it is not idempotent, and running it twice would have every Market Maker
     * fund their event a second time.
     *
     * <p>Does nothing for an LMSR event, or for an order book the file gave nothing to open
     * with: that book opens with nobody holding anything, which the engine allows and the
     * price chart reports by having no opening price.
     *
     * @param marketMaker the event's maker, already checked to be able to afford the
     *                    investment; this method moves the money, it does not vet it
     */
    public void allocateInitial(Event event, User marketMaker) {
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
    }

    /**
     * Has every LMSR event's Market Maker seed its account with b·ln2, so payouts are
     * always covered. Call once, right after loading.
     */
    public void applyInitialSubsidies() {
        for (Event event : events.values()) {
            subsidise(event, requireMarketMaker(event.getId()));
        }
    }

    /**
     * Opens ONE LMSR event: its Market Maker puts up the scoring rule's provable worst-case
     * loss, {@code b·ln2}, and the event's account holds it until settlement.
     *
     * <p><b>The subsidy is the maker's money, not the house's.</b> It used to be conjured
     * into the account by this method, which made opening a market free and closing an
     * untraded one pay: settlement hands whatever the payouts did not need back to the
     * maker, so a user could load a file, touch nothing, close the events they run and walk
     * away with a {@code b·ln2} per event that nobody had ever paid in. Charged to the
     * maker, that same refund is their own stake coming back, an untouched market is a
     * round trip, and {@code Σ user balances + Σ event accounts} is now constant with no
     * exception at all.
     *
     * <p>Split out for the same reason as {@link #allocateInitial}: an event created at
     * runtime needs its own subsidy and nobody else's. The all-events form would charge
     * every Market Maker a second {@code b·ln2} for a market that is already open.
     *
     * <p>Does nothing for an order-book event, which is solvent by a different argument and
     * funded through {@link #allocateInitial} instead.
     *
     * @param marketMaker the event's maker, already checked to be able to afford the
     *                    subsidy; this method moves the money, it does not vet it
     */
    public void subsidise(Event event, User marketMaker) {
        if (!(event.getTradingMethod() instanceof TradingMethod.Lmsr settings)) {
            return;
        }
        double subsidy = settings.subsidy();
        marketMaker.withdraw(subsidy);
        event.getMMAccount().deposit(subsidy);
    }
}
