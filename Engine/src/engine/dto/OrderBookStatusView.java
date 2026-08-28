package engine.dto;

import engine.model.BookTrade;

import java.util.List;

/**
 * The full live state of one order-book event.
 *
 * @param baseValue  what one share of the winning option will pay
 * @param allowMint  whether two buyers can between them create a new pair
 * @param history    newest trade first; {@link BookTrade} is immutable, so it is passed
 *                   through rather than copied into a view, as {@code EventStatusView} does
 *                   with {@code Trade}
 */
public record OrderBookStatusView(int eventId,
                                  String name,
                                  double baseValue,
                                  boolean allowMint,
                                  String marketMaker,
                                  double accountBalance,
                                  double commissionCollected,
                                  List<OptionBookView> options,
                                  List<BookTrade> history,
                                  boolean closed,
                                  String winningOptionName) {
}
