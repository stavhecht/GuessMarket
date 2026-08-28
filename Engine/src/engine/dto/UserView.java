package engine.dto;

import java.util.List;

/**
 * A user's account: their money, and what they hold in every event they have traded in.
 *
 * @param reservedCash  the part of the balance promised to resting buy orders
 * @param availableCash {@code balance - reservedCash}, what they can still commit
 */
public record UserView(String name,
                       double balance,
                       double reservedCash,
                       double availableCash,
                       List<Integer> marketMakerEventIds,
                       List<HoldingView> holdings) {
}
