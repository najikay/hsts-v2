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
 * <p><b>The warning is part of the status line, not a second channel ⚑ (B-24).</b> A
 * {@link SeedMode#LOAD_IF_MISSING} run that finds the database was seeded by a different
 * version of the dataset carries the sentence here, so the console result panel and
 * {@code SeedMain}'s terminal output both show it without either of them having to know the
 * check exists. It is advisory: the run still succeeded and nothing was deleted.
 *
 * @param outcome     what the run did overall
 * @param rowsByTable rows inserted per table, in load order; empty when nothing was inserted
 * @param warning     the drift warning, or {@code ""} when there is nothing to say
 */
public record SeedSummary(SeedOutcome outcome, Map<String, Integer> rowsByTable, String warning) {

    public SeedSummary {
        rowsByTable = Collections.unmodifiableMap(new LinkedHashMap<>(rowsByTable));
        warning = warning == null ? "" : warning;
    }

    /**
     * The common shape: a run with nothing to warn about.
     *
     * @param outcome     what the run did
     * @param rowsByTable rows inserted per table
     */
    public SeedSummary(SeedOutcome outcome, Map<String, Integer> rowsByTable) {
        this(outcome, rowsByTable, "");
    }

    /** @return a summary for a run that changed nothing */
    static SeedSummary nothing(SeedOutcome outcome) {
        return new SeedSummary(outcome, Map.of());
    }

    /** @return {@code true} when this run found the database was seeded by another dataset. */
    public boolean hasWarning() {
        return !warning.isEmpty();
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
        if (hasWarning()) {
            // Last, and on its own line: it is the thing the operator has to act on, and a
            // sentence buried above twenty counts is a sentence nobody reads (B-24).
            text.append(String.format("%n%n")).append(warning);
        }
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
