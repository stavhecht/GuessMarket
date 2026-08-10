package engine.service;

/**
 * Hanson's Logarithmic Market Scoring Rule, for a two-outcome market. Pure and
 * stateless — every method is a function of its arguments alone, which makes this
 * the one class worth testing in complete isolation.
 *
 * <p>C(q) = b · ln(e^(q1/b) + e^(q2/b)), and the price of an outcome is ∂C/∂q_i.
 *
 * <p>Both are evaluated with the larger share count factored out of the exponentials —
 * see {@link #cost}. Written literally they overflow to {@code Infinity} once q/b passes
 * ~709, where {@code Math.exp} runs out of double; for a b of 100 that is a purchase of
 * only ~71,000 shares. Factoring it out costs one subtraction and makes both exact at
 * any share count.
 */
public final class LmsrCalculator {

    /**
     * Total cost of the market being in state (q1, q2).
     *
     * <p>Evaluated as m + b · ln(e^((q1−m)/b) + e^((q2−m)/b)) with m = max(q1, q2), which
     * is the same value rearranged: pulling e^(m/b) out of the log divides each
     * exponential by it and leaves an m behind. Every exponent is then ≤ 0, one of them
     * exactly 0, so the two terms lie in (0, 1] and their sum can never overflow. The
     * smaller term underflowing to 0 is harmless — at that distance it is negligible.
     */
    public double cost(long qYes, long qNo, double b) {
        double max = Math.max(qYes, qNo);
        return max + b * Math.log(Math.exp((qYes - max) / b) + Math.exp((qNo - max) / b));
    }

    /** Instantaneous prices of the two outcomes. Always sums to 1. */
    public double[] prices(long q_yes, long q_no, double b) {
        // Shifted by the max for the same reason as cost(). The shift cancels in the
        // ratio, so the prices are unchanged, and it holds the denominator at ≥ 1 —
        // the literal form could reach Infinity/Infinity and hand back NaN.
        double max = Math.max(q_yes, q_no);
        double eYes = Math.exp((q_yes - max) / b);
        double eNo  = Math.exp((q_no - max) / b);
        double p_yes =  eYes / (eYes + eNo);
        double p_no =  1.0 - p_yes;
        return new double[] { p_yes, p_no };
    }

    /** What it costs to buy {@code delta} more shares of {@code optionIndex}. */
    public double purchaseCost(long qYes, long qNo, int optionIndex, long shares, double b) {
        double before = cost(qYes, qNo, b);
        long newYes = qYes, newNo = qNo;
        if (optionIndex == 0) newYes += shares;
        else newNo  += shares;
        return cost(newYes, newNo, b) - before;
    }

    /**
     * The worst-case loss a two-outcome LMSR market maker can take, and therefore the
     * amount an event account must be seeded with to be guaranteed solvent at
     * settlement: b · ln 2.
     */
    public double initialSubsidy(double b) {
        return cost(0, 0, b);
    }
}
