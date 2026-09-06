package ui.desktop;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.HoldingView;
import engine.dto.OptionBookView;
import engine.dto.OptionView;
import engine.dto.OrderBookStatusView;
import engine.dto.UserView;
import engine.model.BookTrade;
import engine.model.Trade;
import engine.service.MarketEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The arithmetic the screens do on top of the engine's DTOs: counting participants,
 * flattening a trade log into rows, working out what a holding is worth.
 *
 * <p>None of it is market logic: no price is invented here and no money moves. Every
 * figure is either read straight off a DTO or is a sum of figures that were. The one
 * thing that looks like pricing, what a share is worth right now, is a price the engine
 * already quoted, multiplied by a share count.
 *
 * <p>Two of the design's columns cannot be filled for an LMSR event and are reported as
 * {@link Widgets#NONE}: an LMSR {@link Trade} records no buyer, so neither "who" nor "what
 * they invested" survives in the history. The order book's {@link BookTrade} names both
 * sides, and those columns are complete there.
 */
final class MarketData {

    /** What one winning share pays in an LMSR market; an order book has its own base value. */
    private static final double LMSR_SHARE_VALUE = 1.0;

    private MarketData() {
    }

    /** One line of an event's participation log, whichever way the event trades. */
    record Line(int sequence, String user, String optionName, long shares,
                double price, double commission, double total) {
    }

    /**
     * One point of a price chart: both option prices as they stood, and when the trade
     * that moved them happened.
     *
     * <p>{@code at} is {@code null} where no trade is behind the point: the market's
     * opening price, which is where both series begin, and any trade from a {@code .gm}
     * session saved before its kind of trade carried a time.
     */
    record PricePoint(double[] prices, Instant at) {
    }

    /** Where one user stands in one event: what they hold, and what it is worth. */
    record Position(int eventId, String eventName, boolean owner, String optionName,
                    long shares, Double invested, double value, Double ifWins, String status) {

        /** {@code null} for an LMSR event, whose history names no buyer to attribute cost to. */
        Double profit() {
            return invested == null ? null : value - invested;
        }
    }

    // --- events ---

    static boolean isLmsr(EventView event) {
        return "LMSR".equals(event.tradingMethod());
    }

    static boolean isActive(EventView event) {
        return "ACTIVE".equalsIgnoreCase(event.status());
    }

    /**
     * Whether the acting user may seal this event: they are its Market Maker, and it is
     * still open.
     *
     * <p>The same question {@code MarketEngine.closeEvent} asks before it does anything, so
     * a screen can hide the button rather than offer one that is certain to be refused.
     * Every event has a maker, so this is false for everybody but one person.
     */
    static boolean canClose(MarketEngine engine, EventView event) {
        return event != null
                && isActive(event)
                && event.marketMaker() != null
                && event.marketMaker().equals(engine.getCurrentUserName());
    }

    static String methodLabel(EventView event) {
        return isLmsr(event) ? "LMSR" : "Order book";
    }

    static String commissionLabel(EventView event) {
        String when = "PER_PURCHASE".equals(event.commissionMethod()) ? "on purchase" : "on close";
        return Widgets.percent(event.commissionRate()) + "  " + when;
    }

    /** Everyone holding shares in this event, plus its market maker. */
    static int participants(List<UserView> users, EventView event) {
        Set<String> names = new LinkedHashSet<>();
        for (UserView user : users) {
            if (user.marketMakerEventIds().contains(event.id())) {
                names.add(user.name());
            }
            for (HoldingView holding : user.holdings()) {
                if (holding.eventId() == event.id()
                        && (holding.shares()[0] != 0 || holding.shares()[1] != 0)) {
                    names.add(user.name());
                }
            }
        }
        return names.size();
    }

    /** The participation log of an LMSR event, newest first, as the history already is. */
    static List<Line> lines(EventStatusView status) {
        List<Line> lines = new ArrayList<>();
        for (Trade trade : status.history()) {
            double unitPrice = trade.shares() == 0 ? 0 : trade.sharesCost() / trade.shares();
            lines.add(new Line(trade.sequence(), Widgets.NONE, trade.optionName(), trade.shares(),
                    unitPrice, trade.commission(), trade.totalPaid()));
        }
        return lines;
    }

    /** The same log for an order book, where every line names the buyer. */
    static List<Line> lines(OrderBookStatusView status) {
        List<Line> lines = new ArrayList<>();
        for (BookTrade trade : status.history()) {
            lines.add(new Line(trade.sequence(), trade.buyer(), trade.optionName(), trade.quantity(),
                    trade.price(), trade.commission(), trade.totalPaid()));
        }
        return lines;
    }

    /**
     * What each option last traded at as an order book's log runs forward, starting from
     * where the market opened: the series behind the design's price chart.
     *
     * <p>This is bookkeeping, not pricing: each point carries the other option's previous
     * price along unchanged, because nothing happened to it. The LMSR series is the
     * engine's own ({@code MarketEngine.getPriceHistory}), since an LMSR price is a
     * function of outstanding shares rather than of anything written in the log.
     *
     * <p>The first point is the engine's {@code openingPrice}, what the Market Maker's
     * initial allocation cost them a share, so the line begins with the market rather than
     * with its first trade, as the LMSR series does. An event that opened with no allocation
     * has no such price and starts as it always did: at {@code NaN}, which {@link SparkChart}
     * draws as a gap, with the line beginning at the first trade.
     */
    static List<PricePoint> priceSeries(OrderBookStatusView status) {
        List<BookTrade> oldestFirst = new ArrayList<>(status.history());
        Collections.reverse(oldestFirst);

        List<PricePoint> series = new ArrayList<>();
        double open = status.openingPrice() == null ? Double.NaN : status.openingPrice();
        double[] latest = { open, open };
        if (status.openingPrice() != null) {
            series.add(new PricePoint(latest, null));    // the open is nobody's trade
        }
        for (BookTrade trade : oldestFirst) {
            latest = new double[] { latest[0], latest[1] };
            latest[trade.optionIndex()] = trade.price();
            series.add(new PricePoint(latest, trade.createdAt()));
        }
        return series;
    }

    /**
     * The same series for an LMSR event: the engine's own prices, with the time of the
     * trade that produced each one written against it.
     *
     * <p>Two halves of the same history, which is why they are put together here rather
     * than in a screen: {@code MarketEngine.getPriceHistory} replays the event through the
     * scoring rule and hands back the opening price followed by one point per trade,
     * oldest first, while {@code status.history()} is those same trades newest first. So
     * point {@code i} belongs to trade {@code i - 1} of the reversed log, and point 0
     * belongs to none of them. If the two ever disagree in length the extra points simply
     * carry no time, since a price drawn against the wrong moment would be worse than one
     * drawn against no moment at all.
     *
     * @param history what {@code MarketEngine.getPriceHistory} returned for this event
     */
    static List<PricePoint> priceSeries(EventStatusView status, List<double[]> history) {
        List<Trade> oldestFirst = new ArrayList<>(status.history());
        Collections.reverse(oldestFirst);

        List<PricePoint> series = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            boolean fromTrade = i > 0 && i - 1 < oldestFirst.size();
            series.add(new PricePoint(history.get(i),
                    fromTrade ? oldestFirst.get(i - 1).createdAt() : null));
        }
        return series;
    }

    // --- users ---

    static double totalHeld(List<UserView> users) {
        double total = 0;
        for (UserView user : users) {
            total += user.balance();
        }
        return total;
    }

    /**
     * Every event {@code user} is in: each option they hold shares in, with what the
     * holding is worth now and what it pays if that side wins, and then every event they
     * run and hold nothing in.
     *
     * <p><b>Ownership is a position here even when no shares are.</b> A Market Maker is
     * only given shares by an order book, whose opening allocation credits them
     * {@code inital / d} of each option; an LMSR event's creator is given none, and is
     * still its owner. Walking the holdings alone therefore lists a user's order books and
     * silently drops the LMSR events they created, on the one screen that is supposed to
     * say what a user is in, and the row is also what the trade panel below the table
     * selects, so its owner could not close their own event from here either.
     *
     * @param engine consulted for each event's live prices; the caller has already checked
     *               that a file is loaded
     */
    static List<Position> positions(MarketEngine engine, UserView user, List<EventView> events) {
        Map<Integer, EventView> byId = new LinkedHashMap<>();
        for (EventView event : events) {
            byId.put(event.id(), event);
        }

        List<Position> positions = new ArrayList<>();
        Set<Integer> listed = new LinkedHashSet<>();
        for (HoldingView holding : user.holdings()) {
            EventView event = byId.get(holding.eventId());
            if (event == null) {
                continue;
            }
            boolean owner = user.marketMakerEventIds().contains(event.id());
            boolean active = isActive(event);

            double[] prices = new double[2];
            double payout = LMSR_SHARE_VALUE;
            Double[] invested = { null, null };
            String winner;

            if (isLmsr(event)) {
                EventStatusView status = engine.getEventStatus(event.id());
                winner = status.winningOptionName();
                for (int i = 0; i < status.options().size(); i++) {
                    OptionView option = status.options().get(i);
                    prices[i] = option.currentPrice();
                }
            } else {
                OrderBookStatusView status = engine.getOrderBookStatus(event.id());
                winner = status.winningOptionName();
                payout = status.baseValue();
                for (int i = 0; i < status.options().size(); i++) {
                    prices[i] = quote(status.options().get(i));
                }
                double[] spent = investedByOption(status, user.name());
                invested = new Double[] { spent[0], spent[1] };
            }

            for (int i = 0; i < 2; i++) {
                long shares = holding.shares()[i];
                if (shares == 0) {
                    continue;
                }
                String optionName = holding.optionNames().get(i);
                boolean won = optionName.equals(winner);
                double value = active ? shares * prices[i] : (won ? shares * payout : 0.0);
                Double ifWins = active ? shares * payout : null;
                positions.add(new Position(event.id(), event.name(), owner, optionName,
                        shares, invested[i], value, ifWins, event.status()));
                listed.add(event.id());
            }
        }

        // The events they run that the holdings did not reach: every LMSR event they
        // created, and any order book whose opening allocation they have since sold. No
        // option, no shares and nothing invested, because that is the whole truth of it;
        // what the row carries is the event, the Owner role and its status.
        for (EventView event : events) {
            if (user.marketMakerEventIds().contains(event.id()) && !listed.contains(event.id())) {
                positions.add(new Position(event.id(), event.name(), true, Widgets.NONE,
                        0, null, 0.0, null, event.status()));
            }
        }
        return positions;
    }

    /**
     * Balance plus everything they hold, if every side they are still on comes in.
     *
     * <p><b>A settled event adds nothing.</b> Its shares are still on the user, because
     * settlement pays the money out and leaves the holding as the record of what was held,
     * so counting a winning share here would count the same money twice: once in the
     * balance it was just paid into, and again as something still to come. Which is what
     * used to happen, and it left the figure standing above the account for good, since
     * nothing after the close could move it back down.
     *
     * <p>So this is balance plus every <em>open</em> position at what it pays if that side
     * wins, and closing an event moves it: the payout arrives in the balance, and the
     * position that was promising it stops being counted.
     */
    static double potentialOutcome(UserView user, List<Position> positions) {
        double total = user.balance();
        for (Position position : positions) {
            if (position.ifWins() != null) {    // null is a settled event, or a bare ownership
                total += position.ifWins();
            }
        }
        return total;
    }

    /**
     * What {@code name} has put into each option of one order-book event: what they paid as
     * a buyer, less what they took as a seller. A mint has no seller, so only its buyer's
     * side of it is ever counted.
     */
    private static double[] investedByOption(OrderBookStatusView status, String name) {
        double[] spent = new double[2];
        for (BookTrade trade : status.history()) {
            if (name.equals(trade.buyer())) {
                spent[trade.optionIndex()] += trade.totalPaid();
            }
            if (name.equals(trade.seller())) {
                spent[trade.optionIndex()] -= trade.amount();
            }
        }
        return spent;
    }

    /** The price to value a holding at: what it last traded at, else the middle of the quote. */
    static double quote(OptionBookView option) {
        if (option.lastPrice() != null) {
            return option.lastPrice();
        }
        if (option.midPrice() != null) {
            return option.midPrice();
        }
        return 0.0;
    }
}
