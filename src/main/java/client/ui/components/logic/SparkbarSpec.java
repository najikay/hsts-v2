package client.ui.components.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One bar of a mini distribution sparkline (Presentation tier, UI wave 2).
 *
 * <p>The teacher's "last closed sitting" card carries the decile distribution as
 * ten slim bars rather than as a chart: the reader is not analysing it, she is
 * being told the shape in the second before she decides whether to open the
 * sitting. {@link StatChart} remains the real chart and is what the Results
 * screen behind the card still draws.
 *
 * <p>Everything a bar needs is decided here rather than in the node builder, for
 * the reason every {@code logic} class in this package exists: the builder is a
 * loop over these and is on the coverage exclusion list, and the two decisions
 * worth getting right — how tall a bar is, and which one is the mode — are
 * decisions.
 *
 * @param index    the decile, 0 for 0 to 9 and 9 for 90 to 100
 * @param count    how many students landed in it
 * @param fraction its height as a share of the tallest bar, in {@code [0, 1]}
 * @param modal    whether this is the fullest bucket, and so painted in accent
 */
public record SparkbarSpec(int index, int count, double fraction, boolean modal) {

    /**
     * The shortest a non-empty bar is drawn, as a fraction of the tallest.
     *
     * <p>A bucket with one student in it and one with none are different facts,
     * and a bar rounded to nothing tells the reader they are the same. Empty
     * stays empty; anything else gets at least this.
     */
    public static final double MINIMUM_VISIBLE = 0.08;

    public SparkbarSpec {
        if (index < 0) {
            throw new IllegalArgumentException("a decile index is never negative: " + index);
        }
        count = Math.max(count, 0);
        fraction = Math.max(0, Math.min(1, fraction));
    }

    /** @return {@code true} when nobody landed in this decile. */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Turns a stored decile distribution into bars.
     *
     * <p><b>The mode is the first fullest bucket, and only when it is alone in
     * being the fullest.</b> Two buckets tied at the top are not a mode: an
     * accent bar says "this is where the class landed", and saying it twice
     * about a flat distribution is the card inventing a story. A distribution of
     * all zeros — a sitting nobody sat — has no mode either.
     *
     * @param deciles the ten counts, as frozen by the server
     *                ({@code ResultStatistics.deciles()})
     * @return one spec per bucket, in order
     * @throws NullPointerException if {@code deciles} is null
     */
    public static List<SparkbarSpec> of(List<Integer> deciles) {
        Objects.requireNonNull(deciles, "deciles");
        int tallest = 0;
        int tallestAt = -1;
        int tiedAtTop = 0;
        for (int i = 0; i < deciles.size(); i++) {
            int count = deciles.get(i) == null ? 0 : Math.max(deciles.get(i), 0);
            if (count > tallest) {
                tallest = count;
                tallestAt = i;
                tiedAtTop = 1;
            } else if (count == tallest && count > 0) {
                tiedAtTop++;
            }
        }
        int modeAt = tallest > 0 && tiedAtTop == 1 ? tallestAt : -1;

        List<SparkbarSpec> bars = new ArrayList<>(deciles.size());
        for (int i = 0; i < deciles.size(); i++) {
            int count = deciles.get(i) == null ? 0 : Math.max(deciles.get(i), 0);
            double fraction = tallest == 0 ? 0
                    : Math.max((double) count / tallest, count == 0 ? 0 : MINIMUM_VISIBLE);
            bars.add(new SparkbarSpec(i, count, fraction, i == modeAt));
        }
        return List.copyOf(bars);
    }

    /**
     * @return the range this bar covers, for the accessible text and the tooltip.
     *         The last bucket is inclusive of 100, which is how the server
     *         bucketed it
     */
    public String rangeLabel() {
        int low = index * 10;
        int high = index == 9 ? 100 : low + 9;
        return low + " to " + high;
    }
}
