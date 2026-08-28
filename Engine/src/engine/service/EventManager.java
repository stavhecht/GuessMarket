package engine.service;

import engine.exception.EventNotFoundException;
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
            if (!(event.getTradingMethod() instanceof TradingMethod.OrderBook settings)) {
                continue;
            }
            User marketMaker = getMarketMaker(event.getId());
            // The loader has already refused a file where either of these does not hold.
            if (marketMaker == null || settings.initialInvestment() == 0) {
                continue;
            }

            long pairs = settings.initialInvestment() / settings.d();
            marketMaker.withdraw(settings.initialInvestment());
            event.getMMAccount().deposit(settings.initialInvestment());
            for (int optionIndex = 0; optionIndex < Event.OPTION_COUNT; optionIndex++) {
                event.getOption(optionIndex).addShares(pairs);
                marketMaker.addShares(event.getId(), optionIndex, pairs);
            }

            double openingPrice = settings.d() / 2.0;
            for (int optionIndex = 0; optionIndex < Event.OPTION_COUNT; optionIndex++) {
                executor.submit(this, event, marketMaker, optionIndex, OrderSide.SELL, openingPrice, pairs);
            }
        }
    }

    /** Seeds every LMSR event's account with b·ln2 so payouts are always covered. Call once, right after loading. */
    public void applyInitialSubsidies(LmsrCalculator calculator) {
        for (Event event : events.values()) {
            if (event.isLmsr()) {
                event.getMMAccount().deposit(calculator.initialSubsidy(event.getB()));
            }
        }
    }
}
