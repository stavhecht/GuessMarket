package engine.dto;

import java.util.List;

/** An event in the list view — the static description, no market state. */
public record EventView(int id,
                        String name,
                        String description,
                        double commissionRate,
                        String commissionMethod,
                        List<String> optionNames,
                        String status) {
}
