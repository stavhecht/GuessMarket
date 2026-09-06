package engine.dto;

import java.util.List;

/**
 * The outcome of placing one order: what it did on the spot and what is left waiting.
 *
 * <p>The book it went into is not carried here. Both UIs redraw from
 * {@code MarketEngine.getOrderBookStatus} the moment a command returns, so a snapshot
 * taken at this point would be built for nobody and stale by the time anyone asked.
 *
 * @param filled  shares obtained or sold on the spot
 * @param resting shares still waiting in the book: {@code quantity - filled}
 */
public record OrderResult(int sequence,
                          String side,
                          String optionName,
                          double price,
                          long quantity,
                          long filled,
                          long resting,
                          List<FillView> fills) {
}
