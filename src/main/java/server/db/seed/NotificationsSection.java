package server.db.seed;

import org.hibernate.Session;
import server.db.entities.Notification;

import java.time.Instant;
import java.util.List;

/**
 * Seed §11: eight notifications, so the bell is populated at login (E2.15).
 *
 * <p>NFR-21 wants feedback present rather than an empty panel on first sign-in. Every recipient
 * is the person the event actually concerns: rejections and pending notices go to the author,
 * the approval request to the subject coordinator, grade publications to students who sat the
 * exam. Notification 8 exists because S-7 makes the principal read-only, so she can never
 * generate her own activity and would otherwise face a blank screen.
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
 * <p>Notification 8's title states the closed execution's mean as 78. That number comes from
 * seed §9.1's frozen statistics, which are themselves derived from the auto-scores. If those
 * scores change, this string has to change with them; it is listed in the report as a coupling
 * rather than left to be discovered.
 */
final class NotificationsSection implements SeedSection {

    private record Note(String recipient, String type, String title, boolean read, int daysAgo) { }

    private static final List<Note> NOTIFICATIONS = List.of(
            new Note("dana.cohen", "EXAM_REJECTED",
                    "מבחן הוחזר לתיקון: גרסה 1 של \"מבחן אמצע: אלגברה\"", true, 24),
            new Note("dana.cohen", "EXAM_PENDING",
                    "המבחן נשלח לאישור רכזת המקצוע", false, 22),
            new Note("rina.barak", "APPROVAL_REQUEST",
                    "מבחן ממתין לאישורך במקצוע מתמטיקה", false, 22),
            new Note("tamar.shani", "EXAM_REJECTED",
                    "Collections Quiz was returned for revision", false, 20),
            new Note("noa.friedman", "GRADE_PUBLISHED",
                    "הציון שלך במבחן אמצע: אלגברה זמין לצפייה", true, 13),
            new Note("yael.azulay", "GRADE_PUBLISHED",
                    "הציון שלך זמין לצפייה, כולל הערת מורה", false, 13),
            new Note("avi.mizrahi", "GRADING_DUE",
                    "8 attempts awaiting your grade approval", false, 3),
            new Note("principal.avia", "EXECUTION_CLOSED",
                    "בחינה הסתיימה: 8 נבחנים, ממוצע 78", false, 13));

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
            if (alreadyNotified(session, userId, note.type(), note.title())) {
                continue;
            }

            Instant createdAt = context.times().dayOffsetAt(-note.daysAgo(), 8, 0);
            Notification row = new Notification(userId, note.type(), note.title(),
                    null, null, null, createdAt);
            if (note.read()) {
                row.markRead(createdAt.plusSeconds(3600));
            }
            session.persist(row);
            inserted++;
        }

        context.recordInserts("notifications", inserted);
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
