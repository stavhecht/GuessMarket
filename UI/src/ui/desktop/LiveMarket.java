package ui.desktop;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OptionBookView;
import engine.dto.OptionView;
import engine.dto.OrderBookStatusView;
import engine.dto.UserView;
import engine.service.MarketEngine;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The observable mirror the screens follow: a property per option price, per event account
 * and per user balance, re-read from the engine on every {@link DesktopApp#refresh()}.
 *
 * <p>The engine is plain Java and says nothing when it changes — it is asked, never
 * subscribed to. This class is the adapter that gives the UI something to
 * {@code addListener} to: {@link #sync} pushes the engine's current figures into
 * {@link DoubleProperty properties}, and a property fires only when its value actually
 * moves. So a listener on {@link #price} runs on the refreshes where that option really
 * repriced, not on every redraw, and a chart wired to it cannot go stale while some other
 * part of the screen updates.
 *
 * <p>That is also what a {@link Ticker} follows, and why it no longer has to be told which
 * figure a value belongs to: a property firing <em>is</em> a movement, and being pointed at
 * a different property <em>is</em> a change of subject.
 *
 * <p><b>Never hold a permanent binding to one of these properties.</b> {@link #reset} drops
 * them, so a binding made outside the selection path would go on watching an object nothing
 * writes to any more. Re-point on selection instead, the way {@code bindChartTo},
 * {@code bindBalanceTo} and every {@code Ticker.follow} call do.
 *
 * <p>The two histories are not the same kind of thing, on purpose:
 *
 * <ul>
 *   <li><b>Prices</b> keep no history here. The engine already holds the authoritative
 *       one — {@code getPriceHistory} replays an LMSR event through the scoring rule, and
 *       an order book's is read off its trade log — so it covers trades made before this
 *       window opened and survives a session being loaded from disk. The properties below
 *       say <em>when</em> to redraw; the engine still says <em>what</em> to draw.
 *   <li><b>Balances</b> keep history here, because nothing else does. The engine stores a
 *       current balance and no ledger, so the only record of how it got there is the one
 *       accumulated from the moment this window opened. See {@link #balanceHistory}.
 * </ul>
 */
class LiveMarket {

    /** Both option prices of one event, indexed the way the engine indexes them. */
    private final Map<Integer, DoubleProperty[]> prices = new LinkedHashMap<>();

    /**
     * The last price each option of an order book actually traded at — {@code null} until
     * one has, which is why this is an object property and not a {@code double} one. Its
     * neighbour {@link #prices} answers a different question: what the option is worth now,
     * falling back to the middle of the spread when nothing has traded.
     */
    private final Map<Integer, List<ObjectProperty<Double>>> lastPrices = new LinkedHashMap<>();

    /** What each event's own account holds, and what it has taken in commission. */
    private final Map<Integer, DoubleProperty> accounts = new LinkedHashMap<>();
    private final Map<Integer, DoubleProperty> commissions = new LinkedHashMap<>();

    private final Map<String, DoubleProperty> balances = new LinkedHashMap<>();

    /** Every balance this user has held since the window opened, oldest first. */
    private final Map<String, List<Double>> balanceHistory = new LinkedHashMap<>();

    /**
     * What option {@code optionIndex} of {@code eventId} is priced at now.
     *
     * <p>Created on first request so a screen can wire itself to an event before the next
     * {@link #sync} has run; it holds {@code 0} until one has.
     */
    DoubleProperty price(int eventId, int optionIndex) {
        return prices.computeIfAbsent(eventId,
                id -> new DoubleProperty[] { new SimpleDoubleProperty(), new SimpleDoubleProperty() })
                [optionIndex];
    }

    /**
     * What option {@code optionIndex} of an order-book event last traded at, or {@code null}
     * if nothing has traded on it. Only ever set for order-book events; an LMSR option is
     * always priced by the scoring rule and has no such thing.
     */
    ObjectProperty<Double> lastPrice(int eventId, int optionIndex) {
        return lastPrices.computeIfAbsent(eventId,
                id -> List.of(new SimpleObjectProperty<>(), new SimpleObjectProperty<>()))
                .get(optionIndex);
    }

    /** What event {@code eventId}'s account holds now. */
    DoubleProperty account(int eventId) {
        return accounts.computeIfAbsent(eventId, id -> new SimpleDoubleProperty());
    }

    /** What event {@code eventId} has taken in commission so far. */
    DoubleProperty commission(int eventId) {
        return commissions.computeIfAbsent(eventId, id -> new SimpleDoubleProperty());
    }

    /** What {@code userName} holds in cash now. */
    DoubleProperty balance(String userName) {
        return balances.computeIfAbsent(userName, name -> new SimpleDoubleProperty());
    }

    /**
     * The balance timeline behind the Users screen's chart, oldest first.
     *
     * <p>One point per <em>change</em> rather than per refresh, so the line steps where
     * something actually happened to the account, the same way the price chart plots one
     * point per transaction.
     *
     * <p>This starts when the window opens: the engine keeps no per-user ledger, so a
     * balance that moved before this run — including everything behind a session loaded
     * from a {@code .gm} file — is not recoverable and is not drawn. The first point is
     * whatever the balance was when the file came in.
     */
    List<Double> balanceHistory(String userName) {
        // Copied, not handed out: the caller keeps it inside a chart series, and the next
        // sync would otherwise grow a list something is already drawing.
        return List.copyOf(balanceHistory.getOrDefault(userName, List.of()));
    }

    /**
     * Re-reads every figure the charts follow, firing a listener wherever one moved.
     *
     * @param engine already checked by the caller to have a file loaded
     */
    void sync(MarketEngine engine) {
        for (EventView event : engine.getEvents()) {
            // One status per event, read once: it carries the prices, the account and the
            // commission between them, and asking the engine again for each would be three
            // walks of the same state.
            if (MarketData.isLmsr(event)) {
                EventStatusView status = engine.getEventStatus(event.id());
                List<OptionView> options = status.options();
                for (int i = 0; i < options.size(); i++) {
                    price(event.id(), i).set(options.get(i).currentPrice());
                }
                account(event.id()).set(status.accountBalance());
                commission(event.id()).set(status.commissionCollected());
            } else {
                OrderBookStatusView status = engine.getOrderBookStatus(event.id());
                for (int i = 0; i < status.options().size(); i++) {
                    OptionBookView option = status.options().get(i);
                    price(event.id(), i).set(MarketData.quote(option));
                    lastPrice(event.id(), i).set(option.lastPrice());
                }
                account(event.id()).set(status.accountBalance());
                commission(event.id()).set(status.commissionCollected());
            }
        }
        for (UserView user : engine.getUsers()) {
            DoubleProperty held = balance(user.name());
            List<Double> history = balanceHistory.computeIfAbsent(user.name(), name -> new ArrayList<>());
            // An empty history means this user has just arrived: record where they started,
            // so a chart has something to draw before the first trade rather than nothing.
            if (history.isEmpty()) {
                history.add(user.balance());
            } else if (held.get() != user.balance()) {
                history.add(user.balance());
            }
            held.set(user.balance());
        }
    }

    /**
     * Forgets everything, for when a new file replaces the market this was mirroring.
     *
     * <p>Event ids and user names are reused across files, so without this a fresh load
     * would inherit the previous market's balance timeline.
     *
     * <p>Every property is dropped rather than zeroed, which is the reason for the rule in
     * this class's own documentation: after a reset, {@code price(5, 0)} hands back a
     * <em>different</em> object, and anything still listening to the old one will never hear
     * from it again. Zeroing instead would keep the identity but would fire every listener
     * on the way — a following {@link Ticker} would roll all the way down to zero and back
     * up on every file load. Dropping is the right trade because {@code DesktopApp} calls
     * this immediately before a {@code refresh()}, and that refresh re-points every
     * subscription there is.
     */
    void reset() {
        prices.clear();
        lastPrices.clear();
        accounts.clear();
        commissions.clear();
        balances.clear();
        balanceHistory.clear();
    }
}
