package client.features.data;

/**
 * The three things the principal can browse (Presentation tier, E15.2 — F9.3, T-11).
 *
 * <p>A client-side enum rather than a wire one, and deliberately so: unlike
 * {@code ReportDimension}, which parameterises one server mechanism and therefore has to travel,
 * these three name three <em>different</em> reads that already existed or already had to exist.
 * Nothing about a tab is sent anywhere.
 *
 * <p>It is an enum all the same, for the reason {@code ReportDimension} is one on the reports
 * screen: {@code DataTab.values()} builds the segmented control and {@link #segment()} labels it,
 * so the view names none of the three and a fourth tab would not be three hard-coded buttons to
 * find and edit.
 *
 * <p>The order is the order T-11 asks its questions in: the bank, then the exams built from it,
 * then the results of sitting them.
 */
public enum DataTab {

    /** The question bank, school-wide, over {@code BANK_LIST} (F9.3, T-11.1). */
    QUESTIONS("Questions", "questions", "question", "questions"),

    /** The exam catalogue, over {@code DATA_EXAMS_GET} (T-11.2). */
    EXAMS("Exams", "exams", "exam", "exams"),

    /** Closed sittings with their frozen statistics, over {@code DATA_RESULTS_GET} (T-11.2). */
    RESULTS("Results", "results", "sitting", "sittings");

    private final String segment;
    private final String listNoun;
    private final String rowNoun;
    private final String rowNounPlural;

    DataTab(String segment, String listNoun, String rowNoun, String rowNounPlural) {
        this.segment = segment;
        this.listNoun = listNoun;
        this.rowNoun = rowNoun;
        this.rowNounPlural = rowNounPlural;
    }

    /** @return the tab's label on the segmented control: "Questions". */
    public String segment() {
        return segment;
    }

    /**
     * @return what the whole list is called in a failure sentence: "questions". Lower case,
     *         because it is always used mid-sentence
     */
    public String listNoun() {
        return listNoun;
    }

    /**
     * @return what one row is called, singular: "question", "exam", "sitting". "Sitting" rather
     *         than "result", because the Results tab lists one row per <em>sitting</em> and the
     *         results are the figures on it. That is why this is a third word and not
     *         {@link #listNoun()} with an "s" trimmed off it
     */
    public String rowNoun() {
        return rowNoun;
    }

    /** @return what several rows are called: "questions", "exams", "sittings". */
    public String rowNounPlural() {
        return rowNounPlural;
    }

    /** @return the tab the screen opens on. */
    public static DataTab defaultTab() {
        return QUESTIONS;
    }
}
