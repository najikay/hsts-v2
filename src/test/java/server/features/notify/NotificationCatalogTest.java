package server.features.notify;

import common.dto.notify.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Copy-rule tests for the notification catalog (E17.2 — PRD §4.1).
 *
 * <p>This is the class the copy rules can actually be enforced on, which is the
 * reason the catalog exists at all. Three things are checked over the whole set
 * at once, so a sentence added in a later epic cannot quietly break them:
 * <b>no em dashes</b>, <b>every emit point has a type and a destination</b>, and
 * <b>every one of the types is covered</b> — the last one enumerates the enum rather than a
 * number, so a constant added in a later epic fails here until it has a sentence.
 */
class NotificationCatalogTest {

    /** Every draft the catalog can produce, with representative arguments. */
    private static final List<NotificationCatalog.Draft> ALL = List.of(
            NotificationCatalog.approvalRequested("Algebra Midterm", "Dana Cohen", 55L),
            NotificationCatalog.approvalApproved("Algebra Midterm", "Rina Barak", 55L),
            NotificationCatalog.approvalRejected("Algebra Midterm", "Rina Barak",
                    "Question 4 has two correct answers", 55L),
            NotificationCatalog.approvalSuperseded("Algebra Midterm", "Dana Cohen", 56L),
            NotificationCatalog.gradePublished("Algebra Midterm", 7L),
            NotificationCatalog.timeExtended("Algebra Midterm", 10, 3L),
            NotificationCatalog.releaseOpeningSoon("Algebra Midterm", 15, 3L),
            NotificationCatalog.botSourceChanged("Java Programming 21", "Dana Cohen", 4L),
            // U-39: the second sentence under BOT_SOURCE_CHANGED. A type may have more than
            // one, and everyTypeHasASentence checks coverage rather than a one-to-one map.
            NotificationCatalog.botDeleted("Java Programming 21", "Avi Mizrahi", 4L),
            NotificationCatalog.integrityAlert("Java Programming 21", 3L),
            // Both added under B-11, and everyTypeHasASentence is what required them: the seed had
            // been writing GRADING_DUE and EXECUTION_CLOSED into notifications.type since E2.15
            // while neither the enum nor this catalog knew the words, so the read path threw and
            // every staff bell answered INTERNAL. A type with no sentence is a type nothing can
            // send, and a seed row that writes one anyway is exactly how that happened.
            NotificationCatalog.gradingDue("Java Midterm", 8, 3L),
            NotificationCatalog.executionClosed("Algebra Midterm", 8, 72.5, 3L));

    @Test
    @DisplayName("no user-visible sentence contains an em dash (PRD §4.1)")
    void noEmDashes() {
        for (NotificationCatalog.Draft draft : ALL) {
            assertThat(draft.title()).doesNotContain("—").doesNotContain("–");
            assertThat(draft.body()).doesNotContain("—").doesNotContain("–");
        }
    }

    @Test
    @DisplayName("every title is short and every body is a finished sentence")
    void copyShape() {
        for (NotificationCatalog.Draft draft : ALL) {
            assertThat(draft.title()).isNotBlank();
            assertThat(draft.title().length())
                    .as("a title has to fit one line of the panel: %s", draft.title())
                    .isLessThanOrEqualTo(60);
            assertThat(draft.body())
                    .as("a body says what happened, in a sentence: %s", draft.body())
                    .isNotBlank()
                    .endsWith(".");
        }
    }

    @Test
    @DisplayName("every draft points somewhere, so no notification is a dead end")
    void everyDraftIsClickable() {
        for (NotificationCatalog.Draft draft : ALL) {
            assertThat(draft.ref().isNavigable())
                    .as("%s has nowhere to go", draft.type())
                    .isTrue();
            assertThat(draft.ref().entityId()).isNotNull();
        }
    }

    @Test
    @DisplayName("every emit point in the vocabulary is covered")
    void everyTypeHasASentence() {
        Set<NotificationType> covered = ALL.stream()
                .map(NotificationCatalog.Draft::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationType.class)));

        assertThat(covered).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(NotificationType.class));
    }

    @Test
    @DisplayName("⚑ U-39: the delete notice says what happened, not that sources changed")
    void botDeletedSaysTheBotIsGone() {
        NotificationCatalog.Draft draft =
                NotificationCatalog.botDeleted("Java 21", "Avi Mizrahi", 4L);

        assertThat(draft.body())
                .as("a colleague told the sources 'changed' would go looking for a table "
                        + "that is not there any more")
                .isEqualTo("Avi Mizrahi deleted the study bot for Java 21.");
        assertThat(draft.type())
                .as("no new type: the reaction to both is to open the manager and look")
                .isEqualTo(NotificationType.BOT_SOURCE_CHANGED);
        assertThat(draft.title()).isEqualTo("Study bot deleted");
    }

    @Test
    @DisplayName("the supersede notice explains a queue row that vanished (E8.2)")
    void supersedeExplainsItself() {
        NotificationCatalog.Draft draft =
                NotificationCatalog.approvalSuperseded("Algebra Midterm", "Dana Cohen", 56L);

        assertThat(draft.type()).isEqualTo(NotificationType.APPROVAL_SUPERSEDED);
        assertThat(draft.body())
                .contains("Dana Cohen")
                .contains("Algebra Midterm")
                .contains("sent back automatically");
        assertThat(draft.ref().route())
                .as("it points at the newer version, which is the one still worth opening")
                .isEqualTo(NotificationCatalog.ROUTE_APPROVALS);
        assertThat(draft.ref().entityId()).isEqualTo(56L);
    }

    @Test
    @DisplayName("a rejection always carries the reason the author has to act on")
    void rejectionCarriesTheReason() {
        NotificationCatalog.Draft draft = NotificationCatalog.approvalRejected(
                "Algebra Midterm", "Rina Barak", "Question 4 has two correct answers", 55L);

        assertThat(draft.body()).contains("Question 4 has two correct answers");
        assertThat(draft.type()).isEqualTo(NotificationType.APPROVAL_REJECTED);
    }

    @Test
    @DisplayName("a reason typed without punctuation still reads as a sentence")
    void reasonIsFinishedOffAsASentence() {
        assertThat(NotificationCatalog.approvalRejected("Midterm", "Rina", "too short", 1L).body())
                .endsWith("Reason: too short.");
        assertThat(NotificationCatalog.approvalRejected("Midterm", "Rina", "too short. ", 1L).body())
                .as("a reason that already ends in punctuation does not gain a second full stop")
                .endsWith("Reason: too short.");
        assertThat(NotificationCatalog.approvalRejected("Midterm", "Rina", "why only 3 questions?", 1L)
                .body()).endsWith("why only 3 questions?");
    }

    @Test
    @DisplayName("an empty reason says so rather than trailing off")
    void emptyReasonIsStillASentence() {
        assertThat(NotificationCatalog.approvalRejected("Midterm", "Rina", "  ", 1L).body())
                .endsWith("Reason: No reason was given.");
        assertThat(NotificationCatalog.approvalRejected("Midterm", "Rina", null, 1L).body())
                .endsWith("Reason: No reason was given.");
    }

    @Test
    @DisplayName("one minute reads as 'minute', not 'minutes'")
    void singularMinutes() {
        assertThat(NotificationCatalog.timeExtended("Midterm", 1, 3L).body())
                .contains("1 more minute for");
        assertThat(NotificationCatalog.timeExtended("Midterm", 5, 3L).body())
                .contains("5 more minutes for");
        assertThat(NotificationCatalog.releaseOpeningSoon("Midterm", 1, 3L).body())
                .contains("in 1 minute.");
    }

    @Test
    @DisplayName("the integrity alert asks the teacher to look, it does not accuse")
    void integrityAlertIsNeutral() {
        String body = NotificationCatalog.integrityAlert("Java Programming 21", 3L).body();

        assertThat(body).doesNotContainIgnoringCase("cheat");
        assertThat(body).contains("Open the monitor");
    }

    @Test
    @DisplayName("a draft normalises a null body and a null reference")
    void draftNormalises() {
        NotificationCatalog.Draft draft =
                new NotificationCatalog.Draft(NotificationType.TIME_EXTENDED, "t", null, null);

        assertThat(draft.body()).isEmpty();
        assertThat(draft.ref().isNavigable()).isFalse();
    }
}
