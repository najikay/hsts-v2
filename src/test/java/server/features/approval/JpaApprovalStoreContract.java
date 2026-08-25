package server.features.approval;

import common.dto.approval.PreviewAnswerRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.ExamVersionContext;
import server.db.projections.TakeExamQuestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link JpaApprovalStore} driven through the seam the service actually uses (E8).
 *
 * <p>{@code ApprovalRepositoryContract} proves the repositories; this proves the thing in
 * front of them. The distinction matters for the same reason it does in
 * {@code JpaExamStoreContract}: the store is what every rule in {@link ApprovalService} runs
 * against in production, while the unit tests run against {@code InMemoryApprovalStore} — so
 * without this suite the production data path would be reasoned about rather than executed,
 * and a method wired to the wrong repository would pass everything.
 *
 * <p>Two things here exist only in this class. {@code answerKeyOf} is where
 * {@code QuestionVersion} stops travelling and becomes {@link PreviewAnswerRow}, so the
 * ordinal numbering and the key value are established against real rows. And {@code flush()}
 * is what makes {@code @Version} bump inside the rule that wrote, rather than at commit, which
 * is the whole reason the service can turn a lost race into a sentence.
 */
abstract class JpaApprovalStoreContract extends RepositoryTestBase {

    protected static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");
    protected static final int DURATION = 60;

    private ApprovalStore store;

    private ApprovalStore store() {
        if (store == null) {
            store = new JpaApprovalStore(factory());
        }
        return store;
    }

    @Test
    @DisplayName("the store refuses to be built without a factory")
    void factoryIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new JpaApprovalStore(null));
    }

    @Test
    @DisplayName("and refuses a null unit of work rather than opening a transaction for nothing")
    void workIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> store().inTx(null));
    }

    // ===================== Reads =========================================

    @Test
    @DisplayName("the version context reaches the real four-table join")
    void versionContext() {
        long versionId = pendingVersion(1);

        ExamVersionContext ctx = store()
                .inTx(data -> data.versionContext(versionId))
                .orElseThrow();

        assertThat(ctx.examVersionId()).isEqualTo(versionId);
        assertThat(ctx.subjectCode()).isEqualTo(SUBJECT_MATH);
        assertThat(ctx.authorName()).isEqualTo("דנה כהן");
        assertThat(ctx.isPending()).isTrue();
    }

    @Test
    @DisplayName("an unknown version answers empty through the seam too")
    void unknownVersion() {
        Optional<ExamVersionContext> missing = store().inTx(data -> data.versionContext(999_999L));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("the queue read is the scoped one")
    void listsAreWiredToTheRightQueries() {
        // submittedByAuthor was asserted here too until 2026-08-25: it retired with
        // MY_APPROVALS_GET (the E7.10 integration), and the author's list is EXAM_LIST's
        // own read now. Deleting a dead seam method beats keeping a corpse warm with a
        // two-engine test (rule 5 runs in both directions).
        long versionId = pendingVersion(1);

        List<ExamVersionContext> hers = store().inTx(data -> data.pendingFor(rinaId));
        List<ExamVersionContext> danas = store().inTx(data -> data.pendingFor(danaId));

        assertThat(hers).extracting(ExamVersionContext::examVersionId).containsExactly(versionId);
        assertThat(danas).as("Dana teaches the course but coordinates nothing").isEmpty();
    }

    @Test
    @DisplayName("the coordinator lookups are wired to the coordinators table, both directions")
    void coordinatorLookups() {
        List<String> subjects = store().inTx(data -> data.coordinatedSubjects(rinaId));
        Boolean hers = store().inTx(data -> data.coordinates(rinaId, SUBJECT_MATH));
        Boolean notHers = store().inTx(data -> data.coordinates(rinaId, SUBJECT_CS));
        Optional<Long> who = store().inTx(data -> data.coordinatorOf(SUBJECT_MATH));
        Optional<Long> nobody = store().inTx(data -> data.coordinatorOf(SUBJECT_CS));

        assertThat(subjects).containsExactly(SUBJECT_MATH);
        assertThat(hers).isTrue();
        assertThat(notHers).isFalse();
        assertThat(who).contains(rinaId);
        assertThat(nobody).isEmpty();
    }

    @Test
    @DisplayName("the paper comes from the no-correctness projection, and the key comes separately ⚑")
    void paperAndKeyAreSeparateReads() {
        long versionId = pendingVersion(1);

        List<TakeExamQuestion> paper = store().inTx(data -> data.questionsOf(versionId));
        List<PreviewAnswerRow> key = store().inTx(data -> data.answerKeyOf(versionId));

        assertThat(paper).hasSize(3);
        assertThat(paper.get(0).answer1()).isEqualTo("1, 6");
        assertThat(key).hasSize(3);
        assertThat(key).extracting(PreviewAnswerRow::ordinal)
                .as("numbered by the STORED position, which here happens to be 1, 2, 3")
                .containsExactly(1, 2, 3);
        assertThat(key).extracting(PreviewAnswerRow::correctOption)
                .containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(key).extracting(PreviewAnswerRow::questionVersionId)
                .as("and pairs with the student's paper by id")
                .containsExactlyElementsOf(
                        paper.stream().map(TakeExamQuestion::questionVersionId).toList());
    }

    @Test
    @DisplayName("the key is numbered by the stored position, not by counting the rows ⚑")
    void keyIsNumberedByStoredPositionNotByCounting() {
        // The case the rule-5 pass found, and the one no existing fixture could expose. V3
        // constrains `ord` as UNIQUE and >= 1 and nothing more: not contiguous, not starting at
        // 1. A counter over the result list agrees with the stored positions only while an exam
        // happens to be tidy, and every fixture in this suite was tidy.
        //
        // What it costs when they diverge: the coordinator's key panel says "Q3 · option 2"
        // beside a paper whose Q3 is a different question, on the one screen whose entire purpose
        // is checking that the answers are right. She approves having checked the wrong ones.
        //
        // Latent on main until something removes a question from a version. E7's builder is that
        // thing, which is why this fixture is written to what E7 will actually emit.
        long versionId = gappedVersion(9);

        List<PreviewAnswerRow> key = store().inTx(data -> data.answerKeyOf(versionId));

        assertThat(key).extracting(PreviewAnswerRow::ordinal)
                .as("the stored positions, gaps and all")
                .containsExactly(2, 5, 9);

        // And the pairing still holds: each position belongs to its own question, not to the
        // row that happened to be at that place in the list.
        List<TakeExamQuestion> paper = store().inTx(data -> data.questionsOf(versionId));
        assertThat(key).extracting(PreviewAnswerRow::questionVersionId)
                .containsExactlyElementsOf(
                        paper.stream().map(TakeExamQuestion::questionVersionId).toList());
        assertThat(paper).extracting(TakeExamQuestion::ordinal)
                .as("the paper shows the same positions the key does")
                .containsExactly(2, 5, 9);
    }

    @Test
    @DisplayName("question counts come back batched")
    void questionCounts() {
        long first = pendingVersion(1);
        long empty = emptyVersion(2);

        Map<Long, Integer> counts =
                store().inTx(data -> data.questionCounts(List.of(first, empty)));

        assertThat(counts).containsEntry(first, 3).doesNotContainKey(empty);
    }

    // ===================== The one write =================================

    @Test
    @DisplayName("a decision goes through the managed entity, and flush bumps the lock")
    void decisionAndFlush() {
        long versionId = pendingVersion(1);

        Integer lockAfter = store().inTx(data -> {
            ExamVersion row = data.versionForUpdate(versionId).orElseThrow();
            row.approve();
            data.flush();
            return data.versionContext(versionId).orElseThrow().lockVersion();
        });

        assertThat(lockAfter)
                .as("bumped inside the rule that wrote, not at commit")
                .isEqualTo(1);
        assertThat(store().inTx(data -> data.versionContext(versionId)).orElseThrow().status())
                .isEqualTo(ExamVersionStatus.APPROVED);
    }

    @Test
    @DisplayName("versionForUpdate answers empty for a version that is not there")
    void nothingToUpdate() {
        Optional<ExamVersion> missing = store().inTx(data -> data.versionForUpdate(999_999L));
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("the supersede reaches the real status-guarded update")
    void supersede() {
        long first = pendingVersion(1);
        long second = secondVersionOf(first);

        int superseded = store().inTx(data -> {
            long examId = data.versionContext(second).orElseThrow().examId();
            return data.supersedePending(examId, second, ApprovalMessages.SUPERSEDED_REASON);
        });

        assertThat(superseded).isEqualTo(1);
        assertThat(store().inTx(data -> data.versionContext(first)).orElseThrow())
                .satisfies(ctx -> {
                    assertThat(ctx.status()).isEqualTo(ExamVersionStatus.REJECTED);
                    assertThat(ctx.rejectedReason()).isEqualTo(ApprovalMessages.SUPERSEDED_REASON);
                });
    }

    // ===================== End to end, through the service ===============

    @Test
    @DisplayName("the whole feature runs against a real database, not only against the fake ⚑")
    void serviceRunsAgainstTheRealStore() {
        long versionId = pendingVersion(1);
        RecordingNotifier notifier = new RecordingNotifier();
        ApprovalService service = new ApprovalService(store(), notifier);

        var caller = server.core.CallerContext.authenticated(null, rinaId,
                common.dto.auth.Role.COORDINATOR);

        var queue = (common.dto.approval.ApprovalQueue) service.queue(caller,
                common.protocol.Message.request(common.protocol.Verb.APPROVALS_QUEUE_GET, null))
                .getPayload();
        assertThat(queue.rows()).hasSize(1);
        assertThat(queue.rows().get(0).questionCount()).isEqualTo(3);

        var preview = (common.dto.approval.ExamPreview) service.preview(caller,
                common.protocol.Message.request(common.protocol.Verb.EXAM_PREVIEW_GET,
                        new common.dto.approval.ExamPreviewRequest(versionId)))
                .getPayload();
        assertThat(preview.questions()).hasSize(3);
        assertThat(preview.totalPoints()).isEqualTo(100);
        assertThat(preview.teacherOnly().answerKey()).hasSize(3);

        var decision = service.reject(caller,
                common.protocol.Message.request(common.protocol.Verb.EXAM_REJECT,
                        new common.dto.approval.ExamRejectRequest(versionId,
                                "Question 2 has two correct answers. Please fix it.",
                                preview.summary().lockVersion())));

        assertThat(decision.isOk()).isTrue();
        assertThat(store().inTx(data -> data.versionContext(versionId)).orElseThrow())
                .satisfies(ctx -> {
                    assertThat(ctx.status()).isEqualTo(ExamVersionStatus.REJECTED);
                    assertThat(ctx.rejectedReason())
                            .isEqualTo("Question 2 has two correct answers. Please fix it.");
                });
        assertThat(notifier.recipients())
                .as("the author, and nobody else")
                .containsExactly(danaId);
    }

    // ===================== Fixture =======================================

    /** An Algebra exam with one PENDING version of three questions worth 40/30/30. */
    protected final long pendingVersion(int serial) {
        long versionId = emptyVersion(serial);
        addQuestions(versionId);
        return versionId;
    }

    /** An Algebra exam with one PENDING version and no questions. */
    protected final long emptyVersion(int serial) {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) serial,
                    SUBJECT_MATH + COURSE_ALGEBRA + String.format("%02d", serial), danaId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", DURATION,
                    "ענו על כל השאלות.", "לבודק בלבד", ExamVersionStatus.PENDING, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    /**
     * An exam version whose questions sit at positions 2, 5 and 9.
     *
     * <p>Deliberately not contiguous and deliberately not starting at 1, which the schema permits
     * and which E7's builder will produce the first time a teacher removes a question from a
     * draft. Every other fixture here is tidy, and tidy fixtures are why the counter bug survived.
     */
    private long gappedVersion(int serial) {
        long versionId = emptyVersion(serial);
        runInTx(session -> {
            int[] positions = {2, 5, 9};
            for (int index = 0; index < positions.length; index++) {
                short questionSerial = (short) (50 + index);
                Question question = new Question(COURSE_ALGEBRA, questionSerial,
                        COURSE_ALGEBRA + String.format("%03d", questionSerial));
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "שאלה " + questionSerial, "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) (index + 1), "פונקציות", Difficulty.EASY, null, danaId, WHEN);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(
                        versionId, qv.getId(), question.getId(), 30, positions[index]));
            }
        });
        return versionId;
    }

    /** A second pending version of the same exam, as a resubmission produces. */
    private long secondVersionOf(long firstVersionId) {
        return inTx(session -> {
            long examId = session.get(ExamVersion.class, firstVersionId).getExamId();
            ExamVersion version = new ExamVersion(examId, 2, "מבחן אמצע", DURATION,
                    null, null, ExamVersionStatus.PENDING, WHEN.plusSeconds(3600));
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    private void addQuestions(long examVersionId) {
        runInTx(session -> {
            int[] points = {40, 30, 30};
            for (int index = 0; index < 3; index++) {
                short serial = (short) (index + 1);
                Question question = new Question(COURSE_ALGEBRA, serial,
                        COURSE_ALGEBRA + String.format("%03d", serial));
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "שאלה " + serial, "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) (index + 1), "פונקציות", Difficulty.EASY, null, danaId, WHEN);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(
                        examVersionId, qv.getId(), question.getId(), points[index], index + 1));
            }
            session.flush();
        });
    }
}
