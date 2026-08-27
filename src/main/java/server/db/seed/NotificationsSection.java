package server.db.seed;

import common.dto.notify.NotificationType;
import org.hibernate.Session;
import server.db.entities.Notification;
import server.features.notify.NotificationCatalog;

import java.time.Instant;
import java.util.List;

/**
 * Seed §11: nine notifications, so the bell is populated at login (E2.15).
 *
 * <p>NFR-21 wants feedback present rather than an empty panel on first sign-in. Every recipient
 * is the person the event actually concerns: rejections and pending notices go to the author,
 * the approval request to the subject coordinator, grade publications to students who sat the
 * exam. Notification 8 exists because S-7 makes the principal read-only, so she can never
 * generate her own activity and would otherwise face a blank screen.
 *
 * <h2>⚑ B-25: the one bell a grader is most likely to open was the empty one</h2>
 *
 * <p>The eight rows above reach seven recipients and <b>{@code maya.levi} was not one of
 * them</b>. She is the student {@code DEMO_ACCOUNTS.md} and the acceptance table use
 * throughout, and the account {@code DEMO_DAY.md} §2.3 signs in as on the clean-machine pass,
 * so acceptance case 17.3 — "every demoed screen has real content" — found her bell answering
 * {@code 0 items, 0 unread} on a freshly seeded database.
 *
 * <p>Row 9 fixes that with the one notification the seed's own story already justifies: her
 * Algebra midterm grade is approved and visible (§9.1 gives her 60), so a
 * {@code GRADE_PUBLISHED} for it is a row the product itself would have written on approval.
 * <b>It is written in the catalog's exact words</b> — title {@code "Your grade is ready"},
 * body {@code "Your grade for Midterm: Algebra has been published."} — rather than in a
 * seed-only sentence, so what she sees on the day is what a live approval produces.
 *
 * <p>It is also the <b>first seeded notification that deep-links</b>. The catalog's draft
 * carries {@code NavRef.to("grades", attemptId)} and the eight older rows carry no ref at all,
 * so clicking one does nothing. Hers resolves her own attempt on execution {@code 4821} at
 * load time and stores it, which makes the bell → My Grades journey demonstrable rather than
 * described.
 *
 * <p><b>The title had to differ from the other two {@code GRADE_PUBLISHED} rows</b>, because
 * the idempotency key below is recipient + type + title and a repeat would collapse the
 * composite. It does: {@code N-GRADE-NOA} and {@code N-GRADE-YAEL} both open "Your grade
 * for…" / "Your grade is available…", and hers is the catalog's shorter line.
 *
 * <h2>⚑ B-11: the type column is a vocabulary, and this section did not speak it</h2>
 *
 * <p>Until 2026-08-26 the eight rows below carried their types as <b>free strings</b>, and six of
 * the eight were not {@link NotificationType} constants: {@code EXAM_REJECTED} twice,
 * {@code EXAM_PENDING}, {@code APPROVAL_REQUEST} (the constant is {@code APPROVAL_REQUESTED}),
 * {@code GRADING_DUE} and {@code EXECUTION_CLOSED}. Only the two {@code GRADE_PUBLISHED} rows
 * matched. {@code JpaNotificationStore.toDto} maps the column with
 * {@code NotificationType.valueOf}, so on a freshly seeded database {@code NOTIFICATIONS_GET}
 * answered {@code INTERNAL} for every staff account the seed gives a notification to —
 * {@code dana.cohen}, {@code rina.barak}, {@code tamar.shani}, {@code avi.mizrahi} and
 * {@code principal.avia}. {@code michal.sharon}, the one staff account with no seeded row, was the
 * control: her bell opened fine and empty.
 *
 * <p><b>Two different mistakes, fixed two different ways.</b> Four were spelling: the constant
 * existed and this list used another name for it. Two — {@code GRADING_DUE} and
 * {@code EXECUTION_CLOSED} — named events {@link NotificationType} had no constant for at all, so
 * the constants were added rather than the rows re-pointed at a type that means something else.
 * Re-pointing the principal's "sitting finished" row at {@code GRADE_PUBLISHED} would have swapped
 * a crash for a wrong icon and a wrong toast, which is a worse defect for being invisible.
 *
 * <p><b>The record now holds the enum, so this cannot recur.</b> A string type column is a place
 * for a typo to live; the compiler now refuses one, and {@code name()} is written at the last
 * moment — the same call {@code JpaNotificationStore.save} makes, so seeded rows and
 * service-written rows are one shape. The read path was hardened in the same batch: one
 * unparseable row used to take a whole page down, and now it is skipped and logged.
 *
 * <h2>These rows have no natural key, so one is chosen</h2>
 *
 * <p>Every other section keys idempotency on something the schema already makes unique: a
 * username, a display id, a composite primary key. {@code notifications} has none of that, only
 * an {@code AUTO_INCREMENT} id, so re-running the loader would happily insert a ninth copy of
 * the same message.
 *
 * <p>Keyed here on <b>recipient plus type plus title</b>. That is a choice rather than a
 * constraint, and it is flagged for the lead: it means two genuinely distinct notifications
 * with the same title to the same person would collapse into one. Acceptable for a fixed
 * eight-row demo fixture, and wrong the moment the seed grows notifications that repeat. The
 * alternative is an explicit seed id column in §11, which is the content owner's call.
 *
 * <h2>Timestamps and one content coupling</h2>
 *
 * <p>{@code created_at} is NOT NULL and unspecified, so it is derived from the load anchor,
 * spread over recent days so the panel is not eight identical timestamps. Read state comes from
 * §11's own column.
 *
 * <p>Notification 8's title quotes the closed execution's mean, which couples this string to
 * seed §9.1's frozen statistics. That coupling has already bitten once and is worth recording:
 * the title said <b>78</b> until the seed's auto-scores were made reachable and §9.1's mean
 * moved to <b>72.5</b>. Both the document and this list carried 78, so they agreed with each
 * other and with nothing else, and no test could see it. It was caught the first time
 * {@code SeedLoadedDbTest} compared this section against the amended §11, which is the whole
 * argument for that test existing.
 */
final class NotificationsSection implements SeedSection {

    /**
     * @param type the notification's kind. <b>A {@link NotificationType}, not a string</b> — see
     *             the class javadoc's B-11 section for what the string cost
     */
    private record Note(String recipient, NotificationType type, String title, String body,
                        Link link, boolean read, int daysAgo) { }

    /**
     * Where clicking a seeded notification goes (B-25).
     *
     * <p>Resolved at load time rather than written as a number, because the target's id is
     * whatever {@code AUTO_INCREMENT} gave it: {@code executionCode} is the four-character
     * code §9 fixes, and the attempt is the recipient's own on that sitting.
     *
     * @param route         the client route, as {@code NotificationCatalog} spells it
     * @param executionCode the sitting whose attempt is the target
     */
    private record Link(String route, String executionCode) { }

    private static final List<Note> NOTIFICATIONS = List.of(
            // was "EXAM_REJECTED"
            new Note("dana.cohen", NotificationType.APPROVAL_REJECTED,
                    "Exam sent back for revision: version 1 of \"Midterm: Algebra\"", null, null, true, 24),
            // was "EXAM_PENDING". The event is the submission, and the type names the event
            // rather than its audience: here the author is told her own exam went out.
            new Note("dana.cohen", NotificationType.APPROVAL_REQUESTED,
                    "The exam was sent to the subject coordinator for approval", null, null, false, 22),
            // was "APPROVAL_REQUEST" — the constant is APPROVAL_REQUESTED, past tense
            new Note("rina.barak", NotificationType.APPROVAL_REQUESTED,
                    "An exam is waiting for your approval in Mathematics", null, null, false, 22),
            // was "EXAM_REJECTED"
            new Note("tamar.shani", NotificationType.APPROVAL_REJECTED,
                    "Collections Quiz was returned for revision", null, null, false, 20),
            // These two were the only well-formed rows, which is why case 8.5's student bell
            // passed and nobody noticed the staff side.
            new Note("noa.friedman", NotificationType.GRADE_PUBLISHED,
                    "Your grade for Midterm: Algebra is available", null, null, true, 13),
            new Note("yael.azulay", NotificationType.GRADE_PUBLISHED,
                    "Your grade is available, including a teacher's comment", null, null, false, 13),
            // These two named events the enum had no constant for at all, so both are now real
            // constants rather than re-pointed onto a type that means something else.
            new Note("avi.mizrahi", NotificationType.GRADING_DUE,
                    "8 attempts awaiting your grade approval", null, null, false, 3),
            new Note("principal.avia", NotificationType.EXECUTION_CLOSED,
                    "Sitting finished: 8 students, average 72.5", null, null, false, 13),
            // ⚑ B-25. maya.levi is DEMO_DAY §2.3's sign-in account and had no bell at all.
            // The catalog's own words, and the first seeded row that deep-links.
            new Note("maya.levi", NotificationType.GRADE_PUBLISHED,
                    "Your grade is ready",
                    "Your grade for Midterm: Algebra has been published.",
                    new Link(NotificationCatalog.ROUTE_GRADES, "4821"), false, 12));

    @Override
    public String name() {
        return "11 notifications";
    }

    @Override
    public void load(SeedContext context) {
        Session session = context.session();
        int inserted = 0;

        for (Note note : NOTIFICATIONS) {
            long userId = SeedLookup.requireUserId(session, note.recipient());
            if (alreadyNotified(session, userId, note.type().name(), note.title())) {
                continue;
            }

            Instant createdAt = context.times().dayOffsetAt(-note.daysAgo(), 8, 0);
            Long refId = linkTargetFor(session, note, userId);
            // name(), the same call JpaNotificationStore.save makes, which is what makes the
            // seeded rows and the service-written ones one shape rather than two.
            Notification row = new Notification(userId, note.type().name(), note.title(),
                    note.body(), note.link() == null ? null : note.link().route(), refId,
                    createdAt);
            if (note.read()) {
                row.markRead(createdAt.plusSeconds(3600));
            }
            session.persist(row);
            inserted++;
        }

        context.recordInserts("notifications", inserted);
    }

    /**
     * Resolves a seeded deep link's target row id (B-25).
     *
     * <p>A missing target is a {@code null} ref rather than a failed load: the notification is
     * still true and still worth showing, and a seed that refuses to load because one row's
     * click target could not be joined would be a worse outcome than a row that does nothing
     * when clicked — which is what all eight of the older rows do anyway.
     *
     * @return the attempt id to store in {@code ref_id}, or {@code null}
     */
    private static Long linkTargetFor(Session session, Note note, long userId) {
        if (note.link() == null) {
            return null;
        }
        List<Long> executions = SeedLookup.findExecutionByCode(session, note.link().executionCode());
        if (executions.size() != 1) {
            return null;
        }
        return SeedLookup.findAttemptId(session, executions.get(0), userId).orElse(null);
    }

    private static boolean alreadyNotified(Session session, long userId, String type, String title) {
        return session.createQuery("""
                        select count(n) from Notification n
                        where n.userId = :userId and n.type = :type and n.title = :title
                        """, Long.class)
                .setParameter("userId", userId)
                .setParameter("type", type)
                .setParameter("title", title)
                .getSingleResult() > 0;
    }
}
