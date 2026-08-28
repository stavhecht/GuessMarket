package engine.dto;

import java.util.List;

/**
 * What one user holds in one event.
 *
 * @param shares       shares held per option, 0-based like the engine
 * @param lockedShares of those, the ones already offered by a resting sell order
 */
public record HoldingView(int eventId,
                          String eventName,
                          List<String> optionNames,
                          long[] shares,
                          long[] lockedShares) {
}
