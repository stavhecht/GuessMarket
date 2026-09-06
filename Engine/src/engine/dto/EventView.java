package engine.dto;

import java.util.List;

/**
 * An event in the list view: the static description, no market state.
 *
 * @param tradingMethod how the event trades ("LMSR" or "ORDER_BOOK"); the UI routes the
 *                      status and participate commands on it
 * @param marketMaker   the user who funds and settles the event: the one the file names,
 *                      or the one who created it at runtime. Every event has exactly one,
 *                      so the desktop UI shows the close button on this name alone
 */
public record EventView(int id,
                        String name,
                        String description,
                        double commissionRate,
                        String commissionMethod,
                        String tradingMethod,
                        String marketMaker,
                        List<String> optionNames,
                        String status) {
}
