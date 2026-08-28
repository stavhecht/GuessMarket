package engine.dto;

/**
 * One thing that happened to an order the moment it was placed.
 *
 * @param kind         "MATCH" when it met a seller, "MINT" when it and a buyer of the other
 *                     option created a new pair between them
 * @param counterparty the other party, or {@code null} for a mint — there is no seller, the
 *                     shares were created
 */
public record FillView(String kind,
                       String optionName,
                       double price,
                       long quantity,
                       String counterparty,
                       double amount,
                       double commission) {
}
