package engine.dto;

import java.util.List;

/**
 * A user's account: their money, and what they hold in every event they have traded in.
 *
 * @param initialCash   what the file gave them, before anything they or the market did with
 *                      it, including the initial investment a Market Maker pays the moment
 *                      the file is loaded. The engine keeps no ledger, so this is the one
 *                      point of an account's history it can report, and it is what the Users
 *                      screen's balance chart starts from
 * @param reservedCash  the part of the balance promised to resting buy orders
 * @param availableCash {@code balance - reservedCash}, what they can still commit
 */
public record UserView(String name,
                       double initialCash,
                       double balance,
                       double reservedCash,
                       double availableCash,
                       List<Integer> marketMakerEventIds,
                       List<HoldingView> holdings) {
}
