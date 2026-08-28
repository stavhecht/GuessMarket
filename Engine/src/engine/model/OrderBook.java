package engine.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The bids and asks of one event, one book per option, plus the history of everything that
 * has traded in it.
 *
 * <p>Each option is traded on its own: YES at 0.70 and NO at 0.90 is a perfectly possible
 * state of this book. What ties the two together is the base value {@code d} — a pair of
 * shares, one of each option, is always worth exactly {@code d} at settlement, which is
 * what makes minting possible and what the event's account is funded to pay.
 *
 * <p>This class is bookkeeping only: it holds orders in the right order, stamps sequence
 * numbers, remembers trades and answers questions about the state of the market. It never
 * moves money — {@code engine.service.OrderExecutor} does that, because a fill touches two
 * users' accounts and the event's, none of which live here.
 *
 * <p>Ordering is price first, then arrival: bids best (highest) first, asks best (lowest)
 * first, and among equal prices whoever placed theirs first is served first.
 */
public class OrderBook implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final TradingMethod.OrderBook settings;

    // One list per option; index 0 and 1 line up with Event's options.
    private final List<List<Order>> bids = new ArrayList<>();
    private final List<List<Order>> asks = new ArrayList<>();

    private final List<BookTrade> history = new ArrayList<>();
    private final Double[] lastTradePrice = new Double[Event.OPTION_COUNT];

    private int orderCounter;
    private int tradeCounter;

    public OrderBook(TradingMethod.OrderBook settings) {
        this.settings = settings;
        for (int i = 0; i < Event.OPTION_COUNT; i++) {
            bids.add(new ArrayList<>());
            asks.add(new ArrayList<>());
        }
    }

    /** The base value: what one share of the winning option pays, and what a pair costs to mint. */
    public double getD() {
        return settings.d();
    }

    /** Whether this event may create new pairs when two buyers between them cover the base value. */
    public boolean allowsMint() {
        return settings.allowMint();
    }

    public TradingMethod.OrderBook getSettings() {
        return settings;
    }

    // --- placing and removing ---

    /** Stamps the next order number. Kept here so two orders can never share one. */
    public int nextOrderSequence() {
        return ++orderCounter;
    }

    /**
     * Files an order at its place in the queue: after every order that beats it on price,
     * and after every order that matched its price but arrived earlier.
     */
    public void rest(Order order) {
        List<Order> queue = queueFor(order.getSide(), order.getOptionIndex());
        int position = 0;
        while (position < queue.size() && !beats(order, queue.get(position))) {
            position++;
        }
        queue.add(position, order);
    }

    /** Drops orders that have nothing left to fill. */
    public void removeExhausted(int optionIndex) {
        bids.get(optionIndex).removeIf(Order::isExhausted);
        asks.get(optionIndex).removeIf(Order::isExhausted);
    }

    /** Everything still waiting, across both options and both sides — settlement clears the lot. */
    public List<Order> restingOrders() {
        List<Order> all = new ArrayList<>();
        for (int i = 0; i < Event.OPTION_COUNT; i++) {
            all.addAll(bids.get(i));
            all.addAll(asks.get(i));
        }
        return all;
    }

    public void clear() {
        for (int i = 0; i < Event.OPTION_COUNT; i++) {
            bids.get(i).clear();
            asks.get(i).clear();
        }
    }

    /** Buy orders for this option, best price first. */
    public List<Order> getBids(int optionIndex) {
        return Collections.unmodifiableList(bids.get(optionIndex));
    }

    /** Sell orders for this option, best price first. */
    public List<Order> getAsks(int optionIndex) {
        return Collections.unmodifiableList(asks.get(optionIndex));
    }

    // --- what the market looks like ---

    /** The price the last trade in this option went through at, or {@code null} if none has. */
    public Double getLastPrice(int optionIndex) {
        return lastTradePrice[optionIndex];
    }

    /** The most anyone is currently willing to pay — what a seller would get. */
    public Double getBestBid(int optionIndex) {
        List<Order> queue = bids.get(optionIndex);
        return queue.isEmpty() ? null : queue.get(0).getPrice();
    }

    /** The least anyone is currently willing to accept — what a buyer would pay. */
    public Double getBestAsk(int optionIndex) {
        List<Order> queue = asks.get(optionIndex);
        return queue.isEmpty() ? null : queue.get(0).getPrice();
    }

    /**
     * Halfway between the best bid and the best ask: the closest thing to "what a share is
     * worth right now" once the last trade has gone stale. {@code null} unless both sides
     * are represented — an average of one number is not a market.
     */
    public Double getMidPrice(int optionIndex) {
        Double bid = getBestBid(optionIndex);
        Double ask = getBestAsk(optionIndex);
        return bid == null || ask == null ? null : (bid + ask) / 2;
    }

    /** The gap between the two: how far apart buyers and sellers are, and so how liquid this option is. */
    public Double getSpread(int optionIndex) {
        Double bid = getBestBid(optionIndex);
        Double ask = getBestAsk(optionIndex);
        return bid == null || ask == null ? null : ask - bid;
    }

    // --- history ---

    /**
     * Writes one line of history and moves the option's price to it.
     *
     * <p>The {@link BookTrade} is built here rather than passed in, for the reason
     * {@code Event.recordTrade} does the same: the counter never leaves the object that
     * owns it, so two trades cannot be given the same number.
     */
    public BookTrade recordTrade(BookTrade.Kind kind,
                                 int optionIndex,
                                 String optionName,
                                 double price,
                                 long quantity,
                                 String buyer,
                                 String seller,
                                 double commission) {
        BookTrade trade = new BookTrade(++tradeCounter, kind, optionIndex, optionName,
                price, quantity, buyer, seller, commission);
        history.add(trade);
        lastTradePrice[optionIndex] = price;
        return trade;
    }

    public List<BookTrade> getHistory() {
        return Collections.unmodifiableList(history);
    }

    // --- internals ---

    private List<Order> queueFor(OrderSide side, int optionIndex) {
        return side == OrderSide.BUY ? bids.get(optionIndex) : asks.get(optionIndex);
    }

    /**
     * Whether {@code candidate} deserves to go ahead of {@code resting}: a better price
     * only. An equal price does not — the one already waiting was there first.
     */
    private static boolean beats(Order candidate, Order resting) {
        return candidate.getSide() == OrderSide.BUY
                ? candidate.getPrice() > resting.getPrice()
                : candidate.getPrice() < resting.getPrice();
    }
}