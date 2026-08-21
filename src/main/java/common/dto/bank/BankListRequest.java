package common.dto.bank;

import java.io.Serializable;

/**
 * The {@code BANK_LIST} payload: one page of the bank, filtered (Common tier, E6.5 — F2.6).
 *
 * <p>Every filter is optional. A {@code null} or blank string means "do not filter on this",
 * and the compact constructor makes that structural by folding blank to {@code null}, so a
 * client that binds a text field straight to a filter cannot send {@code ""} and get a
 * different answer from the client next to it that sends {@code null}.
 *
 * <h2>The course filter is a convenience, never a boundary</h2>
 *
 * <p>{@code courseCode} narrows the caller's <em>own</em> reachable set; it never widens it.
 * The handler intersects it with the courses the caller may see server-side (a teacher's
 * {@code course_teachers} rows, a coordinator's whole coordinated subject, everything for the
 * principal) rather than trusting the field, so naming somebody else's course code answers an
 * empty page and reveals nothing about it. The list of codes a client offers in its dropdown
 * comes from {@code COURSES_FOR_USER}, not from this contract.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, ranges are handler business: {@code size} is <b>clamped</b> to
 * {@link #MIN_PAGE_SIZE}..{@link #MAX_PAGE_SIZE} and a negative {@code page} is clamped to the
 * first page, both server-side. Clamped rather than refused, because an out-of-range page size
 * is a client bug and not something a teacher can act on, and a bank browse that answers an
 * error dialog instead of rows is worse than one that answers a hundred rows.
 *
 * @param courseCode filter to one course, or {@code null} for every course in reach
 * @param topic      filter to one topic, or {@code null}
 * @param difficulty filter to one difficulty, or {@code null}
 * @param search     free text matched against the stem, or {@code null}
 * @param page       zero-based page index
 * @param size       rows wanted per page; clamped server-side
 */
public record BankListRequest(String courseCode,
                              String topic,
                              Difficulty difficulty,
                              String search,
                              int page,
                              int size) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Smallest page the handler will serve. */
    public static final int MIN_PAGE_SIZE = 1;

    /** Largest page the handler will serve, whatever a client asks for. */
    public static final int MAX_PAGE_SIZE = 100;

    /** What a bank screen asks for when it has no reason to ask for anything else. */
    public static final int DEFAULT_PAGE_SIZE = 40;

    public BankListRequest {
        courseCode = blankToNull(courseCode);
        topic = blankToNull(topic);
        search = blankToNull(search);
    }

    /** @return the first page of everything the caller may see. */
    public static BankListRequest firstPage() {
        return new BankListRequest(null, null, null, null, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * @param page zero-based page index
     * @return this request pointed at another page, filters untouched
     */
    public BankListRequest onPage(int page) {
        return new BankListRequest(courseCode, topic, difficulty, search, page, size);
    }

    /** @return {@code true} when nothing at all is being filtered on. */
    public boolean isUnfiltered() {
        return courseCode == null && topic == null && difficulty == null && search == null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
