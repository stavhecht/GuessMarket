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
    static List<double[]> priceSeries(OrderBookStatusView status) {
        List<BookTrade> oldestFirst = new ArrayList<>(status.history());
        Collections.reverse(oldestFirst);

        List<double[]> series = new ArrayList<>();
        double open = status.openingPrice() == null ? Double.NaN : status.openingPrice();
        double[] latest = { open, open };
        if (status.openingPrice() != null) {
            series.add(latest);
        }
        for (BookTrade trade : oldestFirst) {
            latest = new double[] { latest[0], latest[1] };
            latest[trade.optionIndex()] = trade.price();
            series.add(latest);
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
     * Every option {@code user} holds shares in, with what the holding is worth now and
     * what it pays if that side wins.
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
            }
        }
        return positions;
    }

    /** Balance plus everything they hold, if every side they are on comes in. */
    static double potentialOutcome(UserView user, List<Position> positions) {
        double total = user.balance();
        for (Position position : positions) {
            total += position.ifWins() == null ? position.value() : position.ifWins();
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
