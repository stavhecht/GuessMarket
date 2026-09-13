package api;

import java.util.List;

/**
 * The body of {@code POST /api/events}: an event typed into the form, the same fields
 * {@code MarketEngine.createLmsrEvent} / {@code createOrderBookEvent} take.
 *
 * @param commissionMethod {@code PER_PURCHASE} or {@code ON_CLOSE}, the names of {@link engine.model.CommissionMethod}
 * @param tradingMethod    {@code LMSR} or {@code ORDER_BOOK}, as {@link engine.dto.EventView#tradingMethod()} spells them
 * @param b                the LMSR liquidity; null for an order book
 * @param initialInvestment the order book's opening investment; null for LMSR
 * @param d                the order book's base value; null for LMSR
 * @param allowMint        whether the order book mints; null for LMSR
 */
public record CreateEventRequest(String name,
                                 String description,
                                 int commissionPercent,
                                 String commissionMethod,
                                 List<String> optionNames,
                                 String tradingMethod,
                                 Double b,
                                 Integer initialInvestment,
                                 Integer d,
                                 Boolean allowMint) {

    public static final String LMSR = "LMSR";
    public static final String ORDER_BOOK = "ORDER_BOOK";
}
