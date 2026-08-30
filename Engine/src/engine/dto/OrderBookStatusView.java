package engine.dto;

import engine.model.BookTrade;

import java.util.List;

/**
 * The full live state of one order-book event.
 *
 * @param baseValue    what one share of the winning option will pay
 * @param openingPrice what both options were offered at when the Market Maker's initial
 *                     allocation was placed — half the base value each — so a price chart
 *                     can start where the market did rather than at its first trade;
 *                     {@code null} for an event whose file gave it no initial investment,
 *                     which therefore opened with nothing quoted and has no such price
 * @param allowMint    whether two buyers can between them create a new pair
 * @param history      newest trade first; {@link BookTrade} is immutable, so it is passed
 *                     through rather than copied into a view, as {@code EventStatusView}
 *                     does with {@code Trade}
 */
public record OrderBookStatusView(int eventId,
                                  String name,
                                  double baseValue,
                                  Double openingPrice,
                                  boolean allowMint,
                                  String marketMaker,
                                  double accountBalance,
                                  double commissionCollected,
                                  List<OptionBookView> options,
                                  List<BookTrade> history,
                                  boolean closed,
                                  String winningOptionName) {
}
