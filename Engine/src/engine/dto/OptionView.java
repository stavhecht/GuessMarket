package engine.dto;

/**
 * One outcome as the UI sees it.
 *
 * @param currentPrice LMSR price, in [0,1]; the two options of an event sum to 1
 * @param totalShares  outstanding shares
 */
public record OptionView(String name, double currentPrice, long totalShares) {
}
