package engine.loader;

import engine.model.CommissionMethod;
import engine.model.TradingMethod;

import java.util.List;

/**
 * One event as an exercise-3 file describes it: everything but an id and a Market Maker,
 * which such a file does not carry. The engine supplies both when it adds the event,
 * the id from its own counter and the maker being whoever uploaded the file.
 *
 * <p>Already checked against every rule the loader applies to a file's events, so a
 * definition is something the engine may build an {@link engine.model.Event} from
 * without asking again; it re-applies its own restatement of those rules all the same,
 * so a file and a form cannot drift apart.
 *
 * @param commissionPercent the whole percentage the file states, not yet a rate
 */
public record EventDefinition(String name,
                              String description,
                              int commissionPercent,
                              CommissionMethod commissionMethod,
                              TradingMethod tradingMethod,
                              List<String> optionNames) {

    public EventDefinition {
        optionNames = List.copyOf(optionNames);
    }
}