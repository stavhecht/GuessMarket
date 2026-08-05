package engine.service;

/**
 * Hanson's Logarithmic Market Scoring Rule, for a two-outcome market. Pure and
 * stateless — every method is a function of its arguments alone, which makes this
 * the one class worth testing in complete isolation.
 *
 * <p>C(q) = b · ln(e^(q1/b) + e^(q2/b)), and the price of an outcome is ∂C/∂q_i.
 */
public final class LmsrCalculator {

    /** Total cost of the market being in state (q1, q2). */
    public double cost(long qYes, long qNo, double b) {
        return b * Math.log(Math.exp(qYes / b) + Math.exp(qNo / b));
    }

    /** Instantaneous prices of the two outcomes. Always sums to 1. */
    public double[] prices(long q_yes, long q_no, double b) {
        double eYes = Math.exp(q_yes / b);
        double eNo  = Math.exp(q_no / b);
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
