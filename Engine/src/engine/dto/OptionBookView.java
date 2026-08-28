package engine.dto;

import java.util.List;

/**
 * One option's side of the market: what it last traded at, what it would trade at now, and
 * the orders waiting on both sides.
 *
 * <p>Every price here is nullable, and each is a different kind of silence. {@code lastPrice}
 * is {@code null} until the option has traded at all; {@code bestBid} and {@code bestAsk}
 * are {@code null} when nobody is offering that side; {@code midPrice} and {@code spread}
 * need both sides and are {@code null} unless the market is quoted on each.
 *
 * @param bids buy orders, best (highest) first
 * @param asks sell orders, best (lowest) first
 */
public record OptionBookView(String name,
                             long sharesOutstanding,
                             Double lastPrice,
                             Double bestBid,
                             Double bestAsk,
                             Double midPrice,
                             Double spread,
                             List<OrderLineView> bids,
                             List<OrderLineView> asks) {
}
