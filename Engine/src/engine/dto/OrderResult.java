package engine.dto;

import java.util.List;

/**
 * The outcome of placing one order, with the book as of immediately after it.
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
                          List<FillView> fills,
                          OrderBookStatusView updatedBook) {
}
