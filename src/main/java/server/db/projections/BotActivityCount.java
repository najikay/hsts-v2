package server.db.projections;

import java.time.LocalDate;

/**
 * How many questions a bot was asked on one day (E2.11 / E16.10 — S-34 ⚑).
 *
 * <p>The shape of the {@code GROUP BY} behind the teacher's questions-over-time
 * chart. The date arrives as three integers rather than a {@code LocalDate}
 * because that is what {@code year()} / {@code month()} / {@code day()} return in
 * HQL, and those three functions are the portable way to bucket a
 * {@code DATETIME} across both engines this project tests on — a
 * {@code date_trunc} or a {@code DATE_FORMAT} would tie the query to one of them.
 *
 * <p><b>There is no student column in the query behind this, not even a distinct
 * count.</b> S-34 asks for an anonymous aggregate, and the safest way to give one
 * is to never select the identifying column at all: the projection has nowhere to
 * put it and the SQL never asks for it, so the anonymity is a property of the read
 * rather than of the mapping that follows it. This is the same structural move as
 * {@link TakeExamQuestion} and {@code correct_answer}.
 *
 * @param year       calendar year, UTC
 * @param month      calendar month, 1-12, UTC
 * @param dayOfMonth day of month, UTC
 * @param questions  how many questions were asked that day
 */
public record BotActivityCount(int year, int month, int dayOfMonth, long questions) {

    /** @return the bucket as a date, which is what the wire DTO carries. */
    public LocalDate day() {
        return LocalDate.of(year, month, dayOfMonth);
    }

    /** @return the count as an {@code int}; a course bot never overflows one. */
    public int count() {
        return (int) Math.min(Integer.MAX_VALUE, questions);
    }
}
