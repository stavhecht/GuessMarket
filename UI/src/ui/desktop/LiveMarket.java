package ui.desktop;

import engine.dto.EventView;
import engine.dto.OptionView;
import engine.dto.OrderBookStatusView;
import engine.dto.UserView;
import engine.service.MarketEngine;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The observable mirror the charts follow: one property per option price and one per user
 * balance, re-read from the engine on every {@link DesktopApp#refresh()}.
 *
 * <p>The engine is plain Java and says nothing when it changes — it is asked, never
 * subscribed to. This class is the adapter that gives the UI something to
 * {@code addListener} to: {@link #sync} pushes the engine's current figures into
 * {@link DoubleProperty properties}, and a property fires only when its value actually
 * moves. So a listener on {@link #price} runs on the refreshes where that option really
 * repriced, not on every redraw, and a chart wired to it cannot go stale while some other
 * part of the screen updates.
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
            double[] current = pricesOf(engine, event);
            for (int i = 0; i < current.length; i++) {
                price(event.id(), i).set(current[i]);
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
     */
    void reset() {
        prices.clear();
        balances.clear();
        balanceHistory.clear();
    }

    /**
     * Both current prices of one event, whichever way it trades — the LMSR scoring rule's
     * quote, or the order book's last trade falling back to the middle of its spread.
     */
    private static double[] pricesOf(MarketEngine engine, EventView event) {
        double[] current = new double[2];
        if (MarketData.isLmsr(event)) {
            List<OptionView> options = engine.getEventStatus(event.id()).options();
            for (int i = 0; i < options.size(); i++) {
                current[i] = options.get(i).currentPrice();
            }
        } else {
            OrderBookStatusView status = engine.getOrderBookStatus(event.id());
            for (int i = 0; i < status.options().size(); i++) {
                current[i] = MarketData.quote(status.options().get(i));
            }
        }
        return current;
    }
}