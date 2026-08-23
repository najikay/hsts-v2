package common.dto.report;

/**
 * What a report compares across (Common tier, E15.3 — F9.4, S-37).
 *
 * <p>The parameter of the one report mechanism. F9.4 asks for "avg/median/decile distribution
 * compared across executions of the same teacher / same course / same student", built so that
 * "a new report type is a new strategy class plus a menu entry, nothing else". This enum is the
 * menu entry half: it names a dimension on the wire and carries the label the principal's
 * picker prints, and it is the only thing either side of the socket has to agree on to add a
 * fourth comparison.
 *
 * <p><b>The server holds one strategy per constant</b>
 * ({@code server.features.reports.DimensionStrategy}), registered in one list. Nothing in the
 * engine, the handlers, the DTOs or the screen switches on this enum; they carry it. That is
 * what makes the extensibility claim structural rather than aspirational, and there is a test
 * asserting the engine's source mentions none of these names.
 *
 * <p>The subject id that travels beside a dimension is a {@code String} for every dimension,
 * because the three subjects are not the same kind of thing: a teacher and a student are user
 * ids, a course is a two-character code. Keeping one field of one type is what lets
 * {@link ReportRequest} stay a two-component record instead of growing a component per
 * dimension, and each strategy reads its own id in one place.
 */
public enum ReportDimension {

    /**
     * Every reportable sitting of every exam one teacher <b>wrote</b>.
     *
     * <p>Authorship rather than who ran the room, on E14's precedent (RESULTS_WIRE_CONTRACT §2):
     * the frozen statistics are a property of the paper, and the paper belongs to the person who
     * wrote it. Subject id: the teacher's user id, in decimal.
     */
    BY_TEACHER("By teacher", "Teacher"),

    /**
     * Every reportable sitting of every exam in one course.
     *
     * <p>Subject id: the two-character course code.
     */
    BY_COURSE("By course", "Course"),

    /**
     * Every reportable sitting one student sat.
     *
     * <p>Membership is an attempt on the execution, not a grade: a student who sat it is part of
     * that sitting's history whether or not her paper was marked. Subject id: her user id, in
     * decimal.
     */
    BY_STUDENT("By student", "Student");

    private final String segment;
    private final String subjectNoun;

    ReportDimension(String segment, String subjectNoun) {
        this.segment = segment;
        this.subjectNoun = subjectNoun;
    }

    /** @return the label of this dimension's segment in the picker. */
    public String segment() {
        return segment;
    }

    /** @return what one subject of this dimension is called, for the picker's prompt. */
    public String subjectNoun() {
        return subjectNoun;
    }

    /** @return the default dimension a freshly opened Reports screen lands on. */
    public static ReportDimension defaultDimension() {
        return BY_TEACHER;
    }
}
