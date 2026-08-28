package engine.service;

import engine.exception.EventClosedException;
import engine.exception.InsufficientFundsException;
import engine.exception.InsufficientSharesException;
import engine.exception.InvalidOrderException;
import engine.model.Account;
import engine.model.BookTrade;
import engine.model.CommissionMethod;
import engine.model.Event;
import engine.model.Order;
import engine.model.OrderBook;
import engine.model.OrderSide;
import engine.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs an order through an event's book: match, then mint, then wait.
 *
 * <p>It lives outside {@link engine.model.OrderBook} because a fill is not something a
 * book can do on its own — it moves money between two users and the event's account, none
 * of which the book knows about. The book keeps the queue; this decides what happens.
 *
 * <p>Stateless: everything it needs arrives as an argument, so there is one of these for
 * the whole application and nothing in it to save.
 *
 * <h2>Why this is atomic without a rehearsal</h2>
 *
 * <p>The rest of the engine validates everything before the first mutation. Matching can't
 * work that way — what a fill costs depends on the orders it meets. Instead the submitter
 * is checked against the <em>worst case</em> up front: no fill ever costs a buyer more than
 * their limit price per share (a resting ask is cheaper by definition, and a mint charges
 * the complement {@code d − restingPrice}, which is at most the limit price because the two
 * had to cover {@code d} between them), and a seller can never deliver more than they
 * offered. Once that check passes, every step below can commit as it goes.
 *
 * <p>The counterparties need no check at all: their money was reserved and their shares
 * locked when their order came to rest, so it is still there when the fill arrives.
 */
public class OrderExecutor {

    /**
     * Places an order and takes it as far as it goes.
     *
     * @param state    the loaded session, for looking up the counterparties by name
     * @param event    the event being traded, which must be an active order-book market
     * @param trader   the user placing the order
     * @return the order, with whatever is left of it now resting in the book, and its fills
     */
    public OrderOutcome submit(EventManager state,
                               Event event,
                               User trader,
                               int optionIndex,
                               OrderSide side,
                               double price,
                               long quantity) {

        OrderBook book = event.getOrderBook();
        validate(event, book, trader, optionIndex, side, price, quantity);

        Order order = new Order(book.nextOrderSequence(), trader.getName(), optionIndex, side, price, quantity);
        List<BookTrade> fills = new ArrayList<>();

        if (side == OrderSide.BUY) {
            matchAgainstAsks(state, event, book, order, trader, fills);
            if (book.allowsMint()) {
                mintAgainstOppositeBids(state, event, book, order, trader, fills);
            }
        } else {
            matchAgainstBids(state, event, book, order, trader, fills);
        }

        if (!order.isExhausted()) {
            rest(event, book, order, trader);
        }
        return new OrderOutcome(order, fills);
    }

    // --- the rules, all applied before anything moves ---

    private void validate(Event event,
                          OrderBook book,
                          User trader,
                          int optionIndex,
                          OrderSide side,
                          double price,
                          long quantity) {
        if (!event.isActive()) {
            throw new EventClosedException("Event " + event.getId()
                    + " is closed — its book takes no more orders.");
        }
        if (optionIndex < 0 || optionIndex >= Event.OPTION_COUNT) {
            throw new InvalidOrderException("Option must be 1 or " + Event.OPTION_COUNT + ".");
        }
        if (quantity <= 0) {
            throw new InvalidOrderException("The number of shares must be a positive whole number.");
        }
        // A share is worth between nothing and the base value; outside that the order could
        // never be part of a sane trade.
        if (price <= 0 || price > book.getD()) {
            throw new InvalidOrderException(String.format(
                    "The price must be more than 0 and at most the base value of %.2f.", book.getD()));
        }

        if (side == OrderSide.BUY) {
            double worstCost = quantity * price * (1 + purchaseCommissionRate(event));
            if (trader.getAvailableCash() < worstCost) {
                throw new InsufficientFundsException(String.format(
                        "%s cannot cover that order: it needs at most %.2f and only %.2f is available.",
                        trader.getName(), worstCost, trader.getAvailableCash()));
            }
        } else {
            long available = trader.getAvailableShares(event.getId(), optionIndex);
            if (available < quantity) {
                throw new InsufficientSharesException(String.format(
                        "%s has only %d free share(s) of '%s' to sell, not %d.",
                        trader.getName(), available, event.getOption(optionIndex).getName(), quantity));
            }
        }
    }

    // --- buying from someone who is selling ---

    /**
     * Fills a buy from the cheapest asks first, at <em>their</em> price rather than the
     * buyer's: the resting order named its terms first, so a buyer willing to pay more
     * simply pays less than they offered.
     */
    private void matchAgainstAsks(EventManager state,
                                  Event event,
                                  OrderBook book,
                                  Order incoming,
                                  User buyer,
                                  List<BookTrade> fills) {
        int optionIndex = incoming.getOptionIndex();
        for (Order ask : new ArrayList<>(book.getAsks(optionIndex))) {
            // The asks are cheapest first, so the first one out of reach ends the walk.
            if (incoming.isExhausted() || ask.getPrice() > incoming.getPrice()) {
                break;
            }
            long quantity = Math.min(incoming.getRemaining(), ask.getRemaining());
            double price = ask.getPrice();
            double amount = price * quantity;
            double commission = amount * purchaseCommissionRate(event);
            User seller = state.getUser(ask.getUserName());

            buyer.withdraw(amount + commission);
            buyer.addShares(event.getId(), optionIndex, quantity);
            seller.deliverLockedShares(event.getId(), optionIndex, quantity);
            seller.deposit(amount);
            payCommission(state, event, commission);

            ask.fill(quantity);
            incoming.fill(quantity);
            fills.add(book.recordTrade(BookTrade.Kind.MATCH, optionIndex,
                    event.getOption(optionIndex).getName(), price, quantity,
                    buyer.getName(), seller.getName(), commission));
        }
        book.removeExhausted(optionIndex);
    }

    // --- selling to someone who is buying ---

    /** The same walk from the other side: the highest bids first, at the bid's own price. */
    private void matchAgainstBids(EventManager state,
                                  Event event,
                                  OrderBook book,
                                  Order incoming,
                                  User seller,
                                  List<BookTrade> fills) {
        int optionIndex = incoming.getOptionIndex();
        for (Order bid : new ArrayList<>(book.getBids(optionIndex))) {
            if (incoming.isExhausted() || bid.getPrice() < incoming.getPrice()) {
                break;
            }
            long quantity = Math.min(incoming.getRemaining(), bid.getRemaining());
            double price = bid.getPrice();
            double amount = price * quantity;
            double commission = amount * purchaseCommissionRate(event);
            User buyer = state.getUser(bid.getUserName());

            // The buyer set this money aside when their order came to rest.
            buyer.spendReserved(amount + commission);
            buyer.addShares(event.getId(), optionIndex, quantity);
            seller.removeShares(event.getId(), optionIndex, quantity);
            seller.deposit(amount);
            payCommission(state, event, commission);

            bid.fill(quantity);
            incoming.fill(quantity);
            fills.add(book.recordTrade(BookTrade.Kind.MATCH, optionIndex,
                    event.getOption(optionIndex).getName(), price, quantity,
                    buyer.getName(), seller.getName(), commission));
        }
        book.removeExhausted(optionIndex);
    }

    // --- creating shares out of two buyers ---

    /**
     * Mints new pairs when a buyer of one option meets a buyer of the other and between
     * them they cover the base value.
     *
     * <p>Neither of them is selling anything, so nothing changes hands: a pair of shares is
     * created, one for each of them, and both payments go into the event's account. That
     * account is then holding exactly {@code d} for a pair that will be worth exactly
     * {@code d} at settlement — one of the two shares pays out, the other is worthless —
     * so minting can never leave the event unable to pay.
     *
     * <p>The waiting order is filled at the price it asked for; the arriving one pays the
     * complement, {@code d − thatPrice}, which is at most what it offered and often less.
     */
    private void mintAgainstOppositeBids(EventManager state,
                                         Event event,
                                         OrderBook book,
                                         Order incoming,
                                         User trader,
                                         List<BookTrade> fills) {
        int optionIndex = incoming.getOptionIndex();
        int oppositeIndex = Event.OPTION_COUNT - 1 - optionIndex;
        double baseValue = book.getD();
        double rate = purchaseCommissionRate(event);
        Account account = event.getMMAccount();

        for (Order resting : new ArrayList<>(book.getBids(oppositeIndex))) {
            // Bids are best first, so once one falls short of the base value, so does every
            // one behind it.
            if (incoming.isExhausted() || resting.getPrice() + incoming.getPrice() < baseValue) {
                break;
            }
            long quantity = Math.min(incoming.getRemaining(), resting.getRemaining());
            double restingPrice = resting.getPrice();
            double incomingPrice = baseValue - restingPrice;
            double restingAmount = restingPrice * quantity;
            double incomingAmount = incomingPrice * quantity;
            double restingCommission = restingAmount * rate;
            double incomingCommission = incomingAmount * rate;
            User restingBuyer = state.getUser(resting.getUserName());

            restingBuyer.spendReserved(restingAmount + restingCommission);
            trader.withdraw(incomingAmount + incomingCommission);
            account.deposit(restingAmount + incomingAmount);            // exactly d per pair
            payCommission(state, event, restingCommission + incomingCommission);

            restingBuyer.addShares(event.getId(), oppositeIndex, quantity);
            trader.addShares(event.getId(), optionIndex, quantity);
            event.getOption(oppositeIndex).addShares(quantity);
            event.getOption(optionIndex).addShares(quantity);

            resting.fill(quantity);
            incoming.fill(quantity);
            book.recordTrade(BookTrade.Kind.MINT, oppositeIndex,
                    event.getOption(oppositeIndex).getName(), restingPrice, quantity,
                    restingBuyer.getName(), null, restingCommission);
            fills.add(book.recordTrade(BookTrade.Kind.MINT, optionIndex,
                    event.getOption(optionIndex).getName(), incomingPrice, quantity,
                    trader.getName(), null, incomingCommission));
        }
        book.removeExhausted(oppositeIndex);
    }

    // --- what is left over waits ---

    /**
     * Files the unfilled remainder, setting aside what it promises: the cash for a buy, the
     * shares for a sell. From here it is somebody else's counterparty, and the reservation
     * is what makes that safe.
     */
    private void rest(Event event, OrderBook book, Order order, User trader) {
        if (order.getSide() == OrderSide.BUY) {
            trader.reserve(reservationFor(event, order));
        } else {
            trader.lockShares(event.getId(), order.getOptionIndex(), order.getRemaining());
        }
        book.rest(order);
    }

    /**
     * What a resting buy order still has set aside: its price and commission for every
     * share it has yet to receive. Recomputed rather than stored, because a fill spends
     * exactly this much per share and so leaves the formula true.
     */
    static double reservationFor(Event event, Order order) {
        return order.getRemaining() * order.getPrice() * (1 + purchaseCommissionRate(event));
    }

    /** The rate charged on each purchase — zero on an event that takes its cut at settlement. */
    static double purchaseCommissionRate(Event event) {
        return event.getCommissionMethod() == CommissionMethod.PER_PURCHASE ? event.getCommissionRate() : 0.0;
    }

    /**
     * Hands the commission to the event's Market Maker, who funds the event and is paid for
     * running it, and records the total on the event for display.
     */
    private void payCommission(EventManager state, Event event, double commission) {
        if (commission <= 0) {
            return;
        }
        event.getMMAccount().addCommission(commission);
        User marketMaker = state.getMarketMaker(event.getId());
        if (marketMaker != null) {
            marketMaker.deposit(commission);
        }
    }
}