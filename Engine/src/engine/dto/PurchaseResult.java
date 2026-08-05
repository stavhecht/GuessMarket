package engine.dto;

/**
 * The outcome of one purchase, with the event's state as of immediately after it.
 *
 * @param sharesCost    LMSR cost of the shares
 * @param commission    charged now, or 0 under {@code ON_CLOSE}
 * @param totalPaid     {@code sharesCost + commission}
 */
public record PurchaseResult(String optionName,
                             long sharesBought,
                             double sharesCost,
                             double commission,
                             double totalPaid,
                             EventStatusView updatedStatus) {
}
