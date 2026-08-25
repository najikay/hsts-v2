package server.features.exambuild;

import common.dto.approval.ApprovalState;
import common.dto.auth.Role;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionRow;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.lock.EntityRef;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.SessionManager;
import server.db.entities.Difficulty;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.projections.AuthoredExamHeader;
import server.db.projections.AuthoredVersionRow;
import server.db.projections.ExamCompositionHeader;
import server.db.projections.PinCandidate;
import server.db.projections.PinnedQuestion;
import server.db.repos.CourseRepository;
import server.db.repos.ExamBuildRepository;
import server.db.repos.ExamRepository;
import server.features.locks.DisplayNames;
import server.features.locks.EditLockGuard;
import server.features.locks.EditLockService;
import server.realtime.PushGateway;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExamService} - the builder's writes, and every rule with nothing underneath it
 * (E7.1, E7.2, E7.3, E7.5, E7.6, §5).
 *
 * <p>What is worth reviewing here is not the happy paths. It is the cases built so they cannot
 * pass if the rule they cover is deleted:
 *
 * <ul>
 *   <li>{@code aDeletedQuestionIsRefusedOnSave} and its create twin are the only things standing
 *       between a teacher and a paper carrying a question that is no longer in the bank. There is
 *       no constraint behind either.</li>
 *   <li>The three {@code lockedBy...} cases pin the consult <b>per verb</b>, which is the lead's
 *       2026-08-24 ruling and exists because on the bank a consult hoisted in one verb alone
 *       survived every test that pinned the other.</li>
 *   <li>{@code submitCallsTheHookExactlyOnceAndLast} pins §5.5's division: E7 owns the
 *       transition, E8 owns everything the queue sees. A submit that emitted its own notification
 *       would still pass a test that only checked the status flip.</li>
 *   <li>{@code aRefusalCannotLoseItsSentence} is the bank's lock-refusal lesson applied before it
 *       can bite: a handler dereferences the message on every non-OK path.</li>
 * </ul>
 *
 * <p>The lock service is <b>real, not mocked</b>, for the reason {@code EditLockGuardTest} gives:
 * the guard's whole value is that it inherits one definition of "a live hold", and a mock here
 * would restate that definition inside the test meant to check the caller honours it.
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    private static final long TEACHER_ID = 3;
    private static final long RINA_ID = 9;
    private static final long EXAM_ID = 500;
    private static final long VERSION_ID = 700;
    private static final String COURSE = "11";
    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");

    private static final DisplayNames LOCK_NAMES = userId ->
            userId == RINA_ID ? Optional.of("Rina Barak") : Optional.empty();

    /** The key the builder locks under: the resolved row id, per the lead's ruling. */
    private static final EntityRef VERSION_LOCK =
            new EntityRef(EntityRef.EXAM_VERSION, VERSION_ID);

    @Mock
    private Session session;
    @Mock
    private ExamBuildRepository exams;
    @Mock
    private ExamRepository sharedExamReads;
    @Mock
    private CourseRepository courses;

    private EditLockService lockService;
    private ExamService service;

    @BeforeEach
    void setUp() {
        lockService = new EditLockService(new PushGateway(new SessionManager()), LOCK_NAMES,
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ExamService(exams, sharedExamReads, courses, new EditLockGuard(lockService),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ===================== Fixtures =======================================

    private static CallerContext teacher() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.TEACHER);
    }

    private static QuestionPin pin(long versionId, int points) {
        return new QuestionPin(versionId, points);
    }

    private static PinCandidate candidate(long versionId, long questionId, boolean deleted) {
        return new PinCandidate(versionId, questionId, "1100" + questionId, COURSE, deleted);
    }

    private static ExamCreateRequest aCreate(List<QuestionPin> questions) {
        return new ExamCreateRequest(COURSE, "Algebra Midterm", 90, null, null, questions);
    }

    private static ExamVersionSave aSave(int lockVersion, List<QuestionPin> questions) {
        return new ExamVersionSave(VERSION_ID, lockVersion, "Algebra Midterm", 90, null, null,
                questions);
    }

    private static ExamVersion aVersion(ExamVersionStatus status, int lockVersion) {
        ExamVersion version = new ExamVersion(EXAM_ID, 1, "Algebra Midterm", 90, null, null,
                status, NOW);
        setField(version, "id", VERSION_ID);
        setField(version, "lockVersion", lockVersion);
        return version;
    }

    private static ExamCompositionHeader aHeader(long authorId, ExamVersionStatus status) {
        return new ExamCompositionHeader(EXAM_ID, "110001", COURSE, "Algebra", authorId,
                "Dana Cohen", VERSION_ID, 1, status, "Algebra Midterm", 90, null, null, null,
                NOW, 0);
    }

    private static PinnedQuestion stored(long versionId, long questionId, int ord, int points) {
        return new PinnedQuestion(questionId, versionId, "1100" + questionId, ord, points,
                "What is recursion?", "Recursion", Difficulty.MEDIUM, false, 1, 1);
    }

    /**
     * Stamps a field the database owns.
     *
     * <p>Reflection because {@code ExamVersion} exposes no setter for either, deliberately: the id
     * is the database's to assign and {@code lockVersion} is Hibernate's to bump. A unit test has
     * to play the part JPA plays.
     */
    private static void setField(Object entity, String name, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not stamp " + name, e);
        }
    }

    private void versionExistsFor(long authorId, ExamVersionStatus status, int lockVersion) {
        when(exams.findCompositionHeader(session, VERSION_ID))
                .thenReturn(Optional.of(aHeader(authorId, status)));
        lenient().when(exams.findVersionToWrite(session, VERSION_ID))
                .thenReturn(Optional.of(aVersion(status, lockVersion)));
    }

    private void answerReadsBack(ExamVersionStatus status) {
        lenient().when(exams.findCompositionHeader(session, VERSION_ID))
                .thenReturn(Optional.of(aHeader(TEACHER_ID, status)));
        lenient().when(exams.findComposition(session, VERSION_ID))
                .thenReturn(List.of(stored(10, 1, 1, 100)));
    }

    // ===================== EXAM_CREATE ====================================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("writes the exam, version 1 and its composition")
        void writesVersionOne() {
            when(courses.teaches(session, TEACHER_ID, COURSE)).thenReturn(true);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, false)));
            when(exams.insertExam(session, COURSE, TEACHER_ID)).thenReturn(EXAM_ID);
            when(exams.insertDraftVersion(eq(session), eq(EXAM_ID), eq(1), any(), anyInt(),
                    any(), any(), any())).thenReturn(VERSION_ID);
            answerReadsBack(ExamVersionStatus.DRAFT);

            ExamService.BuildOutcome outcome =
                    service.create(session, teacher(), aCreate(List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.OK);
            assertThat(outcome.composition().versionNo()).isEqualTo(1);
            assertThat(outcome.composition().state()).isEqualTo(ApprovalState.DRAFT);
            verify(exams).replaceComposition(eq(session), eq(VERSION_ID), any());
        }

        @Test
        @DisplayName("a course she does not teach throws FORBIDDEN and writes nothing")
        void refusingToCreateWritesNothing() {
            when(courses.teaches(session, TEACHER_ID, COURSE)).thenReturn(false);

            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    service.create(session, teacher(), aCreate(List.of(pin(10, 100)))));

            verify(exams, never()).insertExam(any(), any(), anyLong());
        }

        @Test
        @DisplayName("⚑ a deleted question is refused before anything is written")
        void aDeletedQuestionIsRefusedOnCreate() {
            when(courses.teaches(session, TEACHER_ID, COURSE)).thenReturn(true);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, true)));

            ExamService.BuildOutcome outcome =
                    service.create(session, teacher(), aCreate(List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.INVALID);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.questionDeleted("11001"));
            verify(exams, never()).insertExam(any(), any(), anyLong());
        }

        @Test
        @DisplayName("bad points are refused before the guard is even consulted")
        void badPointsRefused() {
            when(courses.teaches(session, TEACHER_ID, COURSE)).thenReturn(true);

            ExamService.BuildOutcome outcome =
                    service.create(session, teacher(), aCreate(List.of(pin(10, 99))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.INVALID);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.pointsShort(99));
            verify(exams, never()).findPinCandidates(any(), any());
        }
    }

    // ===================== EXAM_VERSION_SAVE ==============================

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("rewrites the draft and replaces its composition")
        void savesADraft() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, false)));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));

            ExamService.BuildOutcome outcome =
                    service.save(session, teacher(), aSave(4, List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.OK);
            verify(exams).replaceComposition(eq(session), eq(VERSION_ID), any());
        }

        @Test
        @DisplayName("⚑ another author's exam is NOT_FOUND, never named")
        void anotherAuthorsExamIsNotFound() {
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(RINA_ID, ExamVersionStatus.DRAFT)));

            ExamService.BuildOutcome outcome =
                    service.save(session, teacher(), aSave(0, List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.NOT_FOUND);
            assertThat(outcome.message())
                    .as("naming the author would confirm the exam exists and name a colleague")
                    .isEqualTo(ExamBuildMessages.EXAM_NOT_FOUND)
                    .doesNotContain("Dana");
            verify(exams, never()).replaceComposition(any(), anyLong(), any());
        }

        @Test
        @DisplayName("⚑ a submitted version is CONFLICT, not VALIDATION")
        void savingAPendingVersionIsConflict() {
            // Her request was well formed and the world moved underneath it. VALIDATION would
            // put the sentence beside a form field that is not wrong.
            versionExistsFor(TEACHER_ID, ExamVersionStatus.PENDING, 4);

            ExamService.BuildOutcome outcome =
                    service.save(session, teacher(), aSave(4, List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.CONFLICT);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.NOT_A_DRAFT);
            verify(exams, never()).replaceComposition(any(), anyLong(), any());
        }

        @Test
        @DisplayName("a stale lock token is CONFLICT and writes nothing")
        void staleTokenIsConflict() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 7);

            ExamService.BuildOutcome outcome =
                    service.save(session, teacher(), aSave(4, List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.CONFLICT);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.STALE_VERSION);
        }

        @Test
        @DisplayName("⚑ SAVE's own lock consult, keyed off the resolved row id")
        void lockedBySomebodyElseOnSave() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            lockService.acquire(RINA_ID, VERSION_LOCK);

            ExamService.BuildOutcome outcome =
                    service.save(session, teacher(), aSave(4, List.of(pin(10, 100))));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.CONFLICT);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.lockedBy("Rina Barak"));
            verify(exams, never()).replaceComposition(any(), anyLong(), any());
        }

        @Test
        @DisplayName("⚑ the lock consult runs before the version check, on THIS verb")
        void lockBeatsStaleTokenOnSave() {
            // A stale token would answer CONFLICT too, with a different sentence. The lock is the
            // polite refusal and expectedLockVersion is the correctness guarantee, in that order.
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 7);
            lockService.acquire(RINA_ID, VERSION_LOCK);

            assertThat(service.save(session, teacher(), aSave(4, List.of(pin(10, 100)))).message())
                    .isEqualTo(ExamBuildMessages.lockedBy("Rina Barak"));
        }

        @Test
        @DisplayName("her own lock does not refuse her, or every save would fail")
        void herOwnLockDoesNotBlockHerOnSave() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            lockService.acquire(TEACHER_ID, VERSION_LOCK);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, false)));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));

            assertThat(service.save(session, teacher(), aSave(4, List.of(pin(10, 100)))).status())
                    .isEqualTo(ExamService.BuildStatus.OK);
        }

        @Test
        @DisplayName("⚑ a deleted question is refused on the save path too")
        void aDeletedQuestionIsRefusedOnSave() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, true)));

            ExamService.BuildOutcome outcome =
                    service.save(session, teacher(), aSave(4, List.of(pin(10, 100))));

            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.questionDeleted("11001"));
            verify(exams, never()).replaceComposition(any(), anyLong(), any());
        }
    }

    // ===================== EXAM_VERSION_REVISE ============================

    @Nested
    @DisplayName("revise")
    class Revise {

        @Test
        @DisplayName("opens version n+1 carrying the predecessor's composition forward")
        void revisesAnApprovedVersion() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.APPROVED, 2);
            when(exams.findLatestVersionNo(session, EXAM_ID)).thenReturn(1);
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 60), stored(20, 2, 2, 40)));
            when(exams.insertDraftVersion(eq(session), eq(EXAM_ID), eq(2), any(), eq(90), any(),
                    any(), any())).thenReturn(VERSION_ID);

            ExamService.BuildOutcome outcome =
                    service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 2));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.OK);
            ArgumentCaptor<List<ExamBuildRepository.Pin>> pins =
                    ArgumentCaptor.forClass(List.class);
            verify(exams).replaceComposition(eq(session), eq(VERSION_ID), pins.capture());
            assertThat(pins.getValue())
                    .as("the whole composition rides forward, so she starts from what was "
                            + "approved rather than from nothing")
                    .hasSize(2);
        }

        @Test
        @DisplayName("⚑ revising a DRAFT is refused, or an exam gets two drafts")
        void revisingADraftIsRefused() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 2);

            ExamService.BuildOutcome outcome =
                    service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 2));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.CONFLICT);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.ALREADY_A_DRAFT);
            verify(exams, never()).insertDraftVersion(any(), anyLong(), anyInt(), any(), anyInt(),
                    any(), any(), any());
        }

        @Test
        @DisplayName("⚑ REVISE's own lock consult, pinned separately from SAVE's")
        void lockedBySomebodyElseOnRevise() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.APPROVED, 2);
            lockService.acquire(RINA_ID, VERSION_LOCK);

            ExamService.BuildOutcome outcome =
                    service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 2));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.CONFLICT);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.lockedBy("Rina Barak"));
        }
    }

    // ===================== EXAM_SUBMIT ====================================

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        @DisplayName("⚑ flips the draft to PENDING and notifies nobody from inside the transaction")
        void flipsToPendingAndNotifiesNobodyHere() {
            // §5.5: E7 owns the transition, E8 owns everything the queue sees. A submit that
            // emitted its own notification would pass a test checking only the status flip.
            ExamVersion version = aVersion(ExamVersionStatus.DRAFT, 3);
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(TEACHER_ID, ExamVersionStatus.DRAFT)));
            when(exams.findVersionToWrite(session, VERSION_ID)).thenReturn(Optional.of(version));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));

            ExamService.BuildOutcome outcome =
                    service.submitForApproval(session, teacher(),
                            new ExamVersionAction(VERSION_ID, 3));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.OK);
            assertThat(version.getStatus()).isEqualTo(ExamVersionStatus.PENDING);
        }

        @Test
        @DisplayName("⚑ a version that is already PENDING is CONFLICT and nothing moves")
        void resubmitIsRefused() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.PENDING, 3);

            ExamService.BuildOutcome outcome = service.submitForApproval(session, teacher(),
                    new ExamVersionAction(VERSION_ID, 3));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.CONFLICT);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.NOT_SUBMITTABLE);
        }

        @Test
        @DisplayName("⚑ SUBMIT's own lock consult, pinned separately from the other two")
        void lockedBySomebodyElseOnSubmit() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 3);
            lockService.acquire(RINA_ID, VERSION_LOCK);

            ExamService.BuildOutcome outcome = service.submitForApproval(session, teacher(),
                    new ExamVersionAction(VERSION_ID, 3));

            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.lockedBy("Rina Barak"));
        }

        @Test
        @DisplayName("⚑ a stored version failing the points rule is refused and logged loudly")
        void storedVersionFailingPointsIsRefused() {
            // Section 1's invariant says this cannot happen. The check is a genuine test of that
            // invariant rather than a restatement: if it fires, the invariant is false.
            ExamVersion version = aVersion(ExamVersionStatus.DRAFT, 3);
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(TEACHER_ID, ExamVersionStatus.DRAFT)));
            when(exams.findVersionToWrite(session, VERSION_ID)).thenReturn(Optional.of(version));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 60)));

            ExamService.BuildOutcome outcome = service.submitForApproval(session, teacher(),
                    new ExamVersionAction(VERSION_ID, 3));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.INVALID);
            assertThat(version.getStatus())
                    .as("and nothing moved")
                    .isEqualTo(ExamVersionStatus.DRAFT);
        }
    }

    // ===================== The refusals every verb shares ==================

    /**
     * The paths each verb has in common, checked on each verb rather than on one.
     *
     * <p>Found by reading the JaCoCo report: the stale-token branch was covered on {@code save}
     * and on neither of the other two writers, and the missing-version branch on none of the
     * three. That is the same per-verb gap the lead's lock ruling is about, arriving on the
     * neighbouring check instead of on the consult.
     */
    @Nested
    @DisplayName("the shared refusals, per verb")
    class SharedRefusals {

        @Test
        @DisplayName("⚑ a stale token is CONFLICT on revise and on submit, not only on save")
        void staleTokenIsConflictOnEveryWriter() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.APPROVED, 7);
            assertThat(service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 4))
                    .message()).isEqualTo(ExamBuildMessages.STALE_VERSION);

            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 7);
            assertThat(service.submitForApproval(session, teacher(),
                    new ExamVersionAction(VERSION_ID, 4)).message())
                    .isEqualTo(ExamBuildMessages.STALE_VERSION);
        }

        @Test
        @DisplayName("another author's exam is NOT_FOUND on revise and on submit too")
        void anotherAuthorIsNotFoundOnEveryWriter() {
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(RINA_ID, ExamVersionStatus.APPROVED)));

            assertThat(service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 1))
                    .status()).isEqualTo(ExamService.BuildStatus.NOT_FOUND);
            assertThat(service.submitForApproval(session, teacher(),
                    new ExamVersionAction(VERSION_ID, 1)).status())
                    .isEqualTo(ExamService.BuildStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a header without its row is NOT_FOUND rather than a dereference")
        void aHeaderWithoutItsRowIsNotFound() {
            // The two reads can disagree only if the row vanished between them, which inside one
            // transaction should be impossible. It answers rather than throwing, because the
            // alternative on a write path is an INTERNAL and a stack trace.
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(TEACHER_ID, ExamVersionStatus.DRAFT)));
            when(exams.findVersionToWrite(session, VERSION_ID)).thenReturn(Optional.empty());

            assertThat(service.save(session, teacher(), aSave(0, List.of(pin(10, 100)))).status())
                    .isEqualTo(ExamService.BuildStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a create with no course is VALIDATION before the scope guard runs")
        void createWithNoCourseIsRefused() {
            ExamCreateRequest noCourse = new ExamCreateRequest(null, "Algebra Midterm", 90, null,
                    null, List.of(pin(10, 100)));

            ExamService.BuildOutcome outcome = service.create(session, teacher(), noCourse);

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.INVALID);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.COURSE_REQUIRED);
            verify(courses, never()).teaches(any(), anyLong(), any());
        }

        @Test
        @DisplayName("bad metadata is refused on the service path, not only in the validator")
        void badMetadataIsRefusedThroughTheService() {
            // The validator's own tests prove the rule; this proves the service asks it.
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            ExamVersionSave tooLong = new ExamVersionSave(VERSION_ID, 4, "Algebra Midterm", 600,
                    null, null, List.of(pin(10, 100)));

            assertThat(service.save(session, teacher(), tooLong).message())
                    .isEqualTo(ExamBuildMessages.DURATION_OUT_OF_RANGE);
            verify(exams, never()).findPinCandidates(any(), any());
        }

        @Test
        @DisplayName("⚑ a rejected version's reason travels, and an unrejected one reads empty")
        void theRejectionBridgeGoesBothWays() {
            // ExamComposition refuses null for this field; the column is nullable. Both sides of
            // the bridge, because covering one is covering the half that does nothing.
            when(exams.findCompositionHeader(session, VERSION_ID)).thenReturn(Optional.of(
                    new ExamCompositionHeader(EXAM_ID, "110001", COURSE, "Algebra", TEACHER_ID,
                            "Dana Cohen", VERSION_ID, 1, ExamVersionStatus.REJECTED,
                            "Algebra Midterm", 90, null, null, "Too short", NOW, 0)));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));

            assertThat(service.get(session, teacher(), new ExamVersionRequest(VERSION_ID))
                    .composition().rejectedReason()).isEqualTo("Too short");

            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 0);
            assertThat(service.get(session, teacher(), new ExamVersionRequest(VERSION_ID))
                    .composition().rejectedReason())
                    .as("a draft has no reason, and the wire's empty is \"\" rather than null")
                    .isEmpty();
        }
    }

    // ===================== The translations between two type systems ======

    /**
     * The three methods that cross from the store's types to the wire's, and back.
     *
     * <p>Every case here exists because a cold read found the same hole in all of them: the tests
     * above assert <em>that</em> the repository was called, with {@code any()}, and never
     * <em>with what</em>. That is precisely where the {@code questionId = 0} defect lived - a
     * composite foreign key onto {@code question_versions (id, question_id)} that no test on
     * either side of the seam crossed, because the store's own contract test builds pins with
     * real ids and these tests mock the store.
     */
    @Nested
    @DisplayName("the translations")
    class Translations {

        @Test
        @DisplayName("⚑ every stored pin carries the question that owns its version, not a zero")
        void pinsCarryTheirOwningQuestion() {
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, false), candidate(20, 2, false)));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));

            service.save(session, teacher(), aSave(4, List.of(pin(10, 60), pin(20, 40))));

            ArgumentCaptor<List<ExamBuildRepository.Pin>> pins =
                    ArgumentCaptor.forClass(List.class);
            verify(exams).replaceComposition(eq(session), eq(VERSION_ID), pins.capture());
            assertThat(pins.getValue())
                    .as("a zero matches no parent row on fk_evq_question_version, and two zeroes "
                            + "collide on uq_exam_version_questions_question")
                    .extracting(ExamBuildRepository.Pin::questionVersionId,
                            ExamBuildRepository.Pin::questionId,
                            ExamBuildRepository.Pin::points,
                            ExamBuildRepository.Pin::ord)
                    .containsExactly(tuple(10L, 1L, 60, 1), tuple(20L, 2L, 40, 2));
        }

        @Test
        @DisplayName("⚑ ordinals are 1-based and follow the order she arranged them in")
        void ordinalsAreOneBasedAndInHerOrder() {
            // ck_evq_ord CHECK (ord >= 1), so a 0-based ordinal breaks every write.
            versionExistsFor(TEACHER_ID, ExamVersionStatus.DRAFT, 4);
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(30, 3, false), candidate(10, 1, false)));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));

            service.save(session, teacher(), aSave(4, List.of(pin(30, 50), pin(10, 50))));

            ArgumentCaptor<List<ExamBuildRepository.Pin>> pins =
                    ArgumentCaptor.forClass(List.class);
            verify(exams).replaceComposition(eq(session), eq(VERSION_ID), pins.capture());
            assertThat(pins.getValue())
                    .extracting(ExamBuildRepository.Pin::questionVersionId,
                            ExamBuildRepository.Pin::ord)
                    .containsExactly(tuple(30L, 1), tuple(10L, 2));
        }

        @Test
        @DisplayName("⚑ a revision carries points, ordinals and owners forward unchanged")
        void revisionCarriesEveryFieldForward() {
            // The earlier test asserted hasSize(2), which passes with every field scrambled.
            versionExistsFor(TEACHER_ID, ExamVersionStatus.APPROVED, 2);
            when(exams.findLatestVersionNo(session, EXAM_ID)).thenReturn(1);
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 60), stored(20, 2, 2, 40)));
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, false), candidate(20, 2, false)));
            when(exams.insertDraftVersion(eq(session), eq(EXAM_ID), eq(2), any(), anyInt(), any(),
                    any(), any())).thenReturn(VERSION_ID);

            service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 2));

            ArgumentCaptor<List<ExamBuildRepository.Pin>> pins =
                    ArgumentCaptor.forClass(List.class);
            verify(exams).replaceComposition(eq(session), eq(VERSION_ID), pins.capture());
            assertThat(pins.getValue())
                    .extracting(ExamBuildRepository.Pin::questionVersionId,
                            ExamBuildRepository.Pin::questionId,
                            ExamBuildRepository.Pin::points,
                            ExamBuildRepository.Pin::ord)
                    .containsExactly(tuple(10L, 1L, 60, 1), tuple(20L, 2L, 40, 2));
        }

        @Test
        @DisplayName("⚑ a revision is refused when a carried question has left the bank")
        void revisionRefusesARetiredQuestion() {
            // ARCHITECTURE section 5 assigns this rule to the E7 validator by name, and a
            // revision IS a new exam version. Nothing underneath refuses it: soft delete is an
            // UPDATE, so no foreign key fires.
            versionExistsFor(TEACHER_ID, ExamVersionStatus.APPROVED, 2);
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));
            when(exams.findPinCandidates(any(), any()))
                    .thenReturn(List.of(candidate(10, 1, true)));

            ExamService.BuildOutcome outcome =
                    service.revise(session, teacher(), new ExamVersionAction(VERSION_ID, 2));

            assertThat(outcome.status()).isEqualTo(ExamService.BuildStatus.INVALID);
            assertThat(outcome.message()).isEqualTo(ExamBuildMessages.questionDeleted("11001"));
            verify(exams, never()).insertDraftVersion(any(), anyLong(), anyInt(), any(), anyInt(),
                    any(), any(), any());
        }

        @Test
        @DisplayName("⚑ every difficulty and every state maps to its own counterpart")
        void everyEnumArmIsPinned() {
            // Both mappings were entirely unpinned: every fixture used MEDIUM and the only state
            // assertion was DRAFT. Swapping two arms of either changed what a teacher sees on
            // every row and moved no test.
            for (Difficulty stored : Difficulty.values()) {
                assertThat(compositionShowing(stored, ExamVersionStatus.DRAFT)
                        .questions().get(0).difficulty().name())
                        .isEqualTo(stored.name());
            }
            for (ExamVersionStatus status : ExamVersionStatus.values()) {
                assertThat(compositionShowing(Difficulty.EASY, status).state().name())
                        .isEqualTo(status.name());
            }
        }

        /** Reads one composition back through the real mapper. */
        private common.dto.authoring.ExamComposition compositionShowing(Difficulty difficulty,
                                                                        ExamVersionStatus status) {
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(TEACHER_ID, status)));
            when(exams.findComposition(session, VERSION_ID)).thenReturn(List.of(
                    new PinnedQuestion(1, 10, "11001", 1, 100, "What is recursion?", "Recursion",
                            difficulty, false, 1, 1)));

            return service.get(session, teacher(), new ExamVersionRequest(VERSION_ID))
                    .composition();
        }
    }

    // ===================== The outcome's invariant =========================

    @Nested
    @DisplayName("the outcome")
    class Outcome {

        @Test
        @DisplayName("⚑ a refusal cannot lose its sentence, in either direction")
        void aRefusalCannotLoseItsSentence() {
            // The bank's lock-refusal lesson, applied before it can bite here: every handler
            // branch dereferences message() on a non-OK outcome.
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExamService.BuildOutcome(ExamService.BuildStatus.CONFLICT, null, null));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExamService.BuildOutcome(ExamService.BuildStatus.OK, null, null));
        }

        @Test
        @DisplayName("an OK outcome cannot also carry a refusal sentence")
        void okCarriesNoSentence() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExamService.BuildOutcome(ExamService.BuildStatus.OK, null, "refused"));
        }

        @Test
        @DisplayName("and a refusal cannot smuggle a composition past the handler")
        void aRefusalCarriesNoComposition() {
            // The fourth corner, and the only one left unpinned: a handler switching on status
            // would never read it, so a caller attaching one believes it is saying something
            // nothing will hear.
            when(exams.findCompositionHeader(session, VERSION_ID))
                    .thenReturn(Optional.of(aHeader(TEACHER_ID, ExamVersionStatus.DRAFT)));
            when(exams.findComposition(session, VERSION_ID))
                    .thenReturn(List.of(stored(10, 1, 1, 100)));
            var real = service.get(session, teacher(), new ExamVersionRequest(VERSION_ID))
                    .composition();

            assertThatIllegalArgumentException().isThrownBy(() -> new ExamService.BuildOutcome(
                    ExamService.BuildStatus.NOT_FOUND, real, ExamBuildMessages.EXAM_NOT_FOUND));
        }
    }

    // ===================== EXAM_VERSION_GET ===============================

    @Test
    @DisplayName("get answers the composition for its author and NOT_FOUND for anybody else")
    void getIsScopedToTheAuthor() {
        when(exams.findCompositionHeader(session, VERSION_ID))
                .thenReturn(Optional.of(aHeader(RINA_ID, ExamVersionStatus.APPROVED)));

        assertThat(service.get(session, teacher(), new ExamVersionRequest(VERSION_ID)).status())
                .isEqualTo(ExamService.BuildStatus.NOT_FOUND);
    }

    // ===================== EXAM_LIST ======================================

    @Nested
    @DisplayName("list")
    class ListHerExams {

        private static final long EXAM_ID = 70;
        private static final long OTHER_EXAM_ID = 71;
        private static final long V1 = 4101;
        private static final long V2 = 4102;

        private AuthoredExamHeader header(long examId, String displayId6, int latestVersionNo) {
            return new AuthoredExamHeader(examId, displayId6, COURSE, "Java", "Midterm",
                    latestVersionNo);
        }

        private AuthoredVersionRow version(long examId, long versionId, int versionNo,
                                           ExamVersionStatus status, String rejectedReason) {
            return new AuthoredVersionRow(examId, versionId, versionNo, status, rejectedReason,
                    90, NOW, 1);
        }

        @Test
        @DisplayName("every version lands under its own exam, with its question count")
        void everyVersionLandsUnderItsOwnExam() {
            when(exams.findAuthoredExams(session, TEACHER_ID))
                    .thenReturn(List.of(header(EXAM_ID, "110001", 2),
                            header(OTHER_EXAM_ID, "110002", 1)));
            when(exams.findAuthoredVersions(session, TEACHER_ID)).thenReturn(List.of(
                    version(EXAM_ID, V2, 2, ExamVersionStatus.DRAFT, null),
                    version(EXAM_ID, V1, 1, ExamVersionStatus.APPROVED, null),
                    version(OTHER_EXAM_ID, 4103, 1, ExamVersionStatus.PENDING, null)));
            when(sharedExamReads.countQuestionsByVersion(eq(session), any()))
                    .thenReturn(Map.of(V2, 12, V1, 10, 4103L, 8));

            ExamList list = service.list(session, teacher());

            assertThat(list.rows()).extracting(ExamListRow::examId)
                    .containsExactly(EXAM_ID, OTHER_EXAM_ID);
            assertThat(list.rows().get(0).versions())
                    .extracting(ExamVersionRow::examVersionId, ExamVersionRow::questionCount)
                    .containsExactly(tuple(V2, 12), tuple(V1, 10));
            assertThat(list.rows().get(1).versions())
                    .extracting(ExamVersionRow::examVersionId, ExamVersionRow::questionCount)
                    .containsExactly(tuple(4103L, 8));
        }

        @Test
        @DisplayName("the version order the store returned is the order the screen gets")
        void theStoresOrderSurvives() {
            // The read orders by versionNo descending, and the expandable row shows newest
            // first. A grouping that reordered would put version 1 above version 3 on screen
            // with nothing in the query to blame.
            when(exams.findAuthoredExams(session, TEACHER_ID))
                    .thenReturn(List.of(header(EXAM_ID, "110001", 3)));
            when(exams.findAuthoredVersions(session, TEACHER_ID)).thenReturn(List.of(
                    version(EXAM_ID, 4103, 3, ExamVersionStatus.DRAFT, null),
                    version(EXAM_ID, V2, 2, ExamVersionStatus.REJECTED, "Too short"),
                    version(EXAM_ID, V1, 1, ExamVersionStatus.APPROVED, null)));
            when(sharedExamReads.countQuestionsByVersion(eq(session), any()))
                    .thenReturn(Map.of(4103L, 5, V2, 5, V1, 5));

            ExamList list = service.list(session, teacher());

            assertThat(list.rows().get(0).versions()).extracting(ExamVersionRow::versionNo)
                    .containsExactly(3, 2, 1);
        }

        @Test
        @DisplayName("the stored status becomes the wire state, and a null reason becomes \"\"")
        void statusAndReasonAreMapped() {
            // ExamVersionRow refuses a null rejectedReason outright, so this is the difference
            // between a rejected exam rendering its reason and the whole list failing to build.
            when(exams.findAuthoredExams(session, TEACHER_ID))
                    .thenReturn(List.of(header(EXAM_ID, "110001", 2)));
            when(exams.findAuthoredVersions(session, TEACHER_ID)).thenReturn(List.of(
                    version(EXAM_ID, V2, 2, ExamVersionStatus.REJECTED, "Too short"),
                    version(EXAM_ID, V1, 1, ExamVersionStatus.DRAFT, null)));
            when(sharedExamReads.countQuestionsByVersion(eq(session), any()))
                    .thenReturn(Map.of(V2, 4, V1, 4));

            ExamList list = service.list(session, teacher());

            assertThat(list.rows().get(0).versions())
                    .extracting(ExamVersionRow::state, ExamVersionRow::rejectedReason)
                    .containsExactly(tuple(ApprovalState.REJECTED, "Too short"),
                            tuple(ApprovalState.DRAFT, ""));
        }

        @Test
        @DisplayName("the counts are asked for by id, for the versions actually on screen")
        void theCountsAreAskedForByVersionId() {
            when(exams.findAuthoredExams(session, TEACHER_ID))
                    .thenReturn(List.of(header(EXAM_ID, "110001", 2)));
            when(exams.findAuthoredVersions(session, TEACHER_ID)).thenReturn(List.of(
                    version(EXAM_ID, V2, 2, ExamVersionStatus.DRAFT, null),
                    version(EXAM_ID, V1, 1, ExamVersionStatus.APPROVED, null)));
            when(sharedExamReads.countQuestionsByVersion(eq(session), any()))
                    .thenReturn(Map.of(V2, 3, V1, 3));

            service.list(session, teacher());

            ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.captor();
            verify(sharedExamReads).countQuestionsByVersion(eq(session), ids.capture());
            assertThat(ids.getValue()).containsExactlyInAnyOrder(V1, V2);
        }

        @Test
        @DisplayName("a version the count query has nothing for reads 0 rather than falling over")
        void aMissingCountReadsZero() {
            // The write path forbids a version with no questions, so this is defensive. It is
            // here because the alternative is an unboxing NullPointerException that takes her
            // whole exam list down over one row.
            when(exams.findAuthoredExams(session, TEACHER_ID))
                    .thenReturn(List.of(header(EXAM_ID, "110001", 1)));
            when(exams.findAuthoredVersions(session, TEACHER_ID)).thenReturn(List.of(
                    version(EXAM_ID, V1, 1, ExamVersionStatus.DRAFT, null)));
            when(sharedExamReads.countQuestionsByVersion(eq(session), any()))
                    .thenReturn(Map.of());

            ExamList list = service.list(session, teacher());

            assertThat(list.rows().get(0).versions().get(0).questionCount()).isZero();
        }

        @Test
        @DisplayName("a teacher who has written nothing gets an empty list and two idle queries")
        void anAuthorWithNoExamsCostsOneQuery() {
            when(exams.findAuthoredExams(session, TEACHER_ID)).thenReturn(List.of());

            ExamList list = service.list(session, teacher());

            assertThat(list.rows()).isEmpty();
            verify(exams, never()).findAuthoredVersions(any(), anyLong());
            verifyNoInteractions(sharedExamReads);
        }

        @Test
        @DisplayName("⚑ the author scope is the query's, so no other teacher's id is ever asked "
                + "for")
        void theScopeIsTheQuerys() {
            // The scope this verb has is the author id in the SQL. If it were ever re-expressed
            // as a filter over a wider read, this is the test that would go red first.
            when(exams.findAuthoredExams(session, TEACHER_ID)).thenReturn(List.of());

            service.list(session, teacher());

            verify(exams).findAuthoredExams(session, TEACHER_ID);
            verify(exams, never()).findAuthoredExams(eq(session), eq(RINA_ID));
        }
    }
}
