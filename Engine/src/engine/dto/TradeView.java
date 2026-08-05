package engine.dto;

/**
 * One line of purchase history.
 *
 * @param pricePaid what the participant actually paid, commission included
 */
public record TradeView(String optionName, long shares, double pricePaid) {
}
