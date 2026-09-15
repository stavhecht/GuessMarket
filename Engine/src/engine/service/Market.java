package engine.service;

import engine.dto.EventStatusView;
import engine.dto.EventView;
import engine.dto.OrderBookStatusView;
import engine.dto.OrderResult;
import engine.dto.PurchaseQuote;
import engine.dto.PurchaseResult;
import engine.dto.SettlementResult;
import engine.dto.UserView;
import engine.model.CommissionMethod;
import engine.model.OrderSide;

import java.util.List;

/**
 * The market as a screen sees it: exactly the calls the desktop screens make, and nothing
 * about where the market lives.
 *
 * <p>{@link MarketEngine} implements it directly, which is the single-process app. A
 * client that talks to a server implements it over HTTP, and the same screens run
 * unchanged against either. What is deliberately not here is everything about a
 * <em>session</em> (loading a file from a path, saving and restoring a {@code .gm}) because
 * those belong to whoever owns the engine, and a remote client does not.
 *
 * <p>Every method throws {@link engine.exception.EngineException} for anything the user
 * can cause, which is the contract the screens' error handling is written against.
 */
public interface Market {

    /** Whether there is a market to show at all. */
    boolean isFileLoaded();

    /** The name every command is issued as, or {@code null} if nobody has been chosen. */
    String getCurrentUserName();

    UserView selectUser(String name);

    List<UserView> getUsers();

    List<EventView> getEvents();

    EventStatusView getEventStatus(int eventId);

    OrderBookStatusView getOrderBookStatus(int eventId);

    List<double[]> getPriceHistory(int eventId);

    PurchaseQuote quoteParticipation(int eventId, int optionIndex, long shares);

    /**
     * Puts {@code amount} into the current user's account, from nowhere: the one way money
     * enters the market that no other account pays for.
     *
     * @return the account after the deposit
     */
    UserView loadFunds(double amount);

    PurchaseResult participate(int eventId, int optionIndex, long shares);

    OrderResult placeOrder(int eventId, int optionIndex, OrderSide side, double price, long quantity);

    SettlementResult closeEvent(int eventId, int winningOptionIndex);

    EventView createLmsrEvent(String name, String description, int commissionPercent,
                              CommissionMethod commissionMethod, List<String> optionNames, double b);

    EventView createOrderBookEvent(String name, String description, int commissionPercent,
                                   CommissionMethod commissionMethod, List<String> optionNames,
                                   int initialInvestment, int d, boolean allowMint);
}