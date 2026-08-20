package server.db.seed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one seed run did, per table, in a form a console can print (E2.15).
 *
 * <p>The E19.6 server console button calls the loader and shows the result, so the loader has
 * to hand back something displayable rather than only writing to a log the operator cannot
 * see. Text only, by agreement with the lead: this is a status line, not a report.
 *
 * <p>The counts are <em>rows inserted by this run</em>, not rows now in the table. Under
 * {@link SeedMode#LOAD_IF_MISSING} a second run reports zeroes even though the database is
 * full, which is the honest answer to "what did that do".
 *
 * @param outcome     what the run did overall
 * @param rowsByTable rows inserted per table, in load order; empty when nothing was inserted
 */
public record SeedSummary(SeedOutcome outcome, Map<String, Integer> rowsByTable) {

    public SeedSummary {
        rowsByTable = Collections.unmodifiableMap(new LinkedHashMap<>(rowsByTable));
    }

    /** @return a summary for a run that changed nothing */
    static SeedSummary nothing(SeedOutcome outcome) {
        return new SeedSummary(outcome, Map.of());
    }

    /** @return total rows inserted across every table */
    public int totalRows() {
        return rowsByTable.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * A short human-readable summary, one line per table with a count.
     *
     * <p>No em dashes: PRD §4.1 forbids them in user-visible text, and this string is shown
     * in the server console.
     *
     * @return the summary as displayable text
     */
    public String toText() {
        StringBuilder text = new StringBuilder(headline());
        rowsByTable.forEach((table, rows) ->
                text.append(String.format("%n  %-24s %5d", table, rows)));
        return text.toString();
    }

    private String headline() {
        return switch (outcome) {
            case LOADED -> "Seed loaded: " + totalRows() + " rows inserted.";
            case UNCHANGED -> "Seed already present, nothing inserted.";
            case RESEEDED -> "Database reseeded: " + totalRows()
                    + " rows inserted, timestamps resolved against the current clock.";
            case CANCELLED -> "Reseed cancelled. Nothing was deleted and nothing was inserted.";
        };
    }
}
