package server.db.seed;

import java.util.List;

/**
 * The dataset, assembled in dependency order (E2.15).
 *
 * <p>One entry per numbered section of {@code docs/seed/SEED_CONTENT.md}, in the order they
 * have to run: a section may depend on everything above it and on nothing below it. Keeping
 * the order in one list rather than spread across the sections themselves means the
 * dependency chain is readable in a single screen, and means a reviewer can check it against
 * the document's own table of contents.
 */
public final class SeedDataset {

    private SeedDataset() {
        // static factory, no instances
    }

    /**
     * Every section of the dataset, in the order they must be loaded.
     *
     * <p><b>This list is currently partial, and deliberately so.</b> PR 3a loads seed sections
     * 1 to 8 and 11. Sections 9 and 10, the executions with their attempts and grades and the
     * bot content, are absent because the document cannot be transcribed as written: §9's
     * auto-scores are not reachable totals for the exam they belong to, {@code attempt_answers}
     * is never specified at all, and {@code bot_sources.raw} is NOT NULL with a
     * {@code LENGTH > 0} check while no bytes are supplied for any of the eight sources. Those
     * are content questions for the owner and the lead, not gaps a loader should paper over by
     * inventing plausible numbers. They arrive in PR 3b, and because idempotency is per row,
     * adding them is purely additive: nothing here changes.
     *
     * @return the sections implemented so far, in dependency order
     */
    public static List<SeedSection> sections() {
        return List.of(
                new SubjectsSection(),
                new UsersSection(),
                new FacultySection(),
                new EnrollmentsSection(),
                new QuestionBankSection(),
                new ExamsSection(),
                new ExecutionsSection(),
                new AttemptsSection(),
                new GradesSection(),
                new BotSection(),
                new NotificationsSection());
    }
}
