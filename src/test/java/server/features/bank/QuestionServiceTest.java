package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.DeleteOutcome;
import common.dto.bank.Difficulty;
import common.dto.bank.ImageAction;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.protocol.ErrorCode;
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
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.entities.User;
import server.db.ids.AllocatedId;
import server.db.ids.QuestionIdAllocator;
import server.db.projections.ReferencingExam;
import server.db.repos.CourseRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QuestionService} - the three bank writes (E6.1, E6.3, E6.4).
 *
 * <p>The tests that matter here are not the happy paths. They are the ones that would still pass
 * if a rule were deleted, so each is built so that it cannot:
 *
 * <ul>
 *   <li>{@code aCourseSheDoesNotTeachIsNotFound} pins the contract's section 6 decision that scope
 *       and absence are one answer. Change the guard to throw {@code FORBIDDEN} and it fails.</li>
 *   <li>{@code refusingToCreateWritesNothing} and {@code blockedDeleteLeavesTheQuestionAlone} pin
 *       the absence of a side effect, which a test asserting only the return value cannot see.</li>
 *   <li>{@code stripsTheCourseCodeBeforeTheGuard} uses U+3000, which {@code strip()} removes and
 *       {@code trim()} does not. Note which of its two assertions carries the weight: the
 *       allocator one. {@code Authorization.teachesCourse} strips the code again on its own way
 *       through, so the guard sees {@code "11"} either way and the {@code teaches} assertion
 *       would survive the change it looks like it is catching.</li>
 *   <li>{@code editWritesANewVersionAndLeavesTheOldOneAlone} asserts the previous row is
 *       unmodified, which is the whole of ADR-011 and is invisible to a test that only inspects
 *       the new version.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    private static final long TEACHER_ID = 3;
    private static final long QUESTION_ID = 4200;
    private static final String COURSE = "11";
    private static final String DISPLAY_ID = "11007";
    private static final Instant NOW = Instant.parse("2026-08-21T20:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-08-01T09:00:00Z");

    private static final List<String> ANSWERS =
            List.of("Encapsulation", "Inheritance", "Polymorphism", "Abstraction");

    @Mock
    private Session session;
    @Mock
    private QuestionRepository questions;
    @Mock
    private CourseRepository courses;
    @Mock
    private UserRepository users;
    @Mock
    private QuestionIdAllocator ids;
    @Mock
    private User author;

    private QuestionService service;

    @BeforeEach
    void setUp() {
        service = new QuestionService(questions, courses, users, ids,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ===================== Fixtures =======================================

    private static CallerContext teacher() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.TEACHER);
    }

    /** The detail payload needs a course name and an author name on every path. */
    private void stubDetailLookups() {
        lenient().when(courses.findName(session, COURSE)).thenReturn(Optional.of("Java"));
        lenient().when(users.findById(session, TEACHER_ID)).thenReturn(Optional.of(author));
        lenient().when(author.getFullName()).thenReturn("Dana Cohen");
    }

    private void teaches(boolean answer) {
        when(courses.teaches(session, TEACHER_ID, COURSE)).thenReturn(answer);
    }

    private static Question aQuestion() {
        Question question = new Question(COURSE, (short) 7, DISPLAY_ID);
        setId(question, QUESTION_ID);
        return question;
    }

    private static QuestionVersion aVersion(int versionNo, byte[] image) {
        return new QuestionVersion(QUESTION_ID, versionNo, "What is encapsulation?",
                ANSWERS.get(0), ANSWERS.get(1), ANSWERS.get(2), ANSWERS.get(3),
                (byte) 1, "OOP", server.db.entities.Difficulty.MEDIUM,
                image, TEACHER_ID, EARLIER);
    }

    private static QuestionDraft aDraft(String courseCode, byte[] image) {
        return new QuestionDraft(courseCode, "What is encapsulation?", ANSWERS, 1, "OOP",
                Difficulty.MEDIUM, image);
    }

    private static QuestionEdit anEdit(int baseVersionNo, ImageAction action, byte[] image) {
        return new QuestionEdit(DISPLAY_ID, baseVersionNo, "What is encapsulation, precisely?",
                ANSWERS, 2, "OOP", Difficulty.HARD, action, image);
    }

    /**
     * Gives a persisted entity the id the database would have given it.
     *
     * <p>Reflection because {@link Question} exposes no setter, deliberately: the id is the
     * database's to assign and production code never sets one. The service needs it between
     * {@code persist} and {@code flush}, so a unit test has to play the part JPA plays.
     *
     * @param entity the entity to stamp
     * @param id     the id to give it
     */
    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not stamp an id on " + entity.getClass(), e);
        }
    }

    /** Makes {@code persist} assign an id to the identity row, as the database would. */
    private void persistAssignsIds() {
        doAnswer(call -> {
            if (call.getArgument(0) instanceof Question question) {
                setId(question, QUESTION_ID);
            }
            return null;
        }).when(session).persist(any());
    }

    private QuestionVersion persistedVersion() {
        ArgumentCaptor<Object> persisted = ArgumentCaptor.forClass(Object.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).persist(persisted.capture());
        return persisted.getAllValues().stream()
                .filter(QuestionVersion.class::isInstance)
                .map(QuestionVersion.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no QuestionVersion was persisted"));
    }

    // ===================== create =========================================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("allocates a display id and writes version 1")
        void writesVersionOne() {
            teaches(true);
            stubDetailLookups();
            persistAssignsIds();
            when(ids.allocate(session, COURSE)).thenReturn(new AllocatedId(7, DISPLAY_ID));

            QuestionDetail detail = service.create(session, teacher(), aDraft(COURSE, null));

            QuestionVersion written = persistedVersion();
            assertThat(written.getVersionNo()).isEqualTo(QuestionService.FIRST_VERSION);
            assertThat(written.getQuestionId()).isEqualTo(QUESTION_ID);
            assertThat(written.getA1()).isEqualTo("Encapsulation");
            assertThat(written.getA4()).isEqualTo("Abstraction");
            assertThat(written.getCorrectAnswer()).isEqualTo((byte) 1);
            assertThat(written.getCreatedBy()).isEqualTo(TEACHER_ID);
            assertThat(written.getCreatedAt()).isEqualTo(NOW);

            assertThat(detail.displayId5()).isEqualTo(DISPLAY_ID);
            assertThat(detail.versionNo()).isEqualTo(1);
            assertThat(detail.latestVersionNo()).isEqualTo(1);
            assertThat(detail.answers()).containsExactlyElementsOf(ANSWERS);
            assertThat(detail.courseName()).isEqualTo("Java");
            assertThat(detail.authorName()).isEqualTo("Dana Cohen");
        }

        @Test
        @DisplayName("stores the illustration on the first version")
        void storesTheImage() {
            teaches(true);
            stubDetailLookups();
            persistAssignsIds();
            when(ids.allocate(session, COURSE)).thenReturn(new AllocatedId(7, DISPLAY_ID));
            byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

            QuestionDetail detail = service.create(session, teacher(), aDraft(COURSE, png));

            assertThat(persistedVersion().getImage()).containsExactly(png);
            assertThat(detail.hasImage()).isTrue();
        }

        @Test
        @DisplayName("throws FORBIDDEN for a course she does not teach, and writes nothing")
        void refusingToCreateWritesNothing() {
            teaches(false);

            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> service.create(session, teacher(), aDraft(COURSE, null)))
                    .matches(refused -> refused.errorCode() == ErrorCode.FORBIDDEN);

            // The half a return-value assertion cannot see. A guard that refused after
            // allocating would still satisfy the exception and burn a serial.
            verify(session, never()).persist(any());
            verify(ids, never()).allocate(any(), any());
        }

        @Test
        @DisplayName("strips the course code before the guard, and strip is not trim")
        void stripsTheCourseCodeBeforeTheGuard() {
            // U+3000 IDEOGRAPHIC SPACE: strip() removes it, trim() does not, and code2 is
            // CHAR(2) under a PAD SPACE collation, so the untrimmed form matches the row in
            // SQL while failing Java equality.
            //
            // The allocator assertion is the one that catches strip->trim. The teaches
            // assertion below it does not and cannot: Authorization.teachesCourse strips the
            // code itself before consulting the directory, so the lambda is handed "11"
            // whichever function the service used. Both lines are kept, because the guard
            // seeing the normalised code is worth asserting in its own right, but only one of
            // them is evidence for the sentence above. The strip is therefore implemented
            // twice, here and in the guard, and nothing checks that the two agree.
            teaches(true);
            stubDetailLookups();
            persistAssignsIds();
            when(ids.allocate(session, COURSE)).thenReturn(new AllocatedId(7, DISPLAY_ID));

            service.create(session, teacher(), aDraft(COURSE + "　", null));

            verify(courses).teaches(session, TEACHER_ID, COURSE);
            verify(ids).allocate(session, COURSE);
        }
    }

    // ===================== update =========================================

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("an unknown display id is NOT_FOUND")
        void unknownIsNotFound() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID)).thenReturn(Optional.empty());

            QuestionService.EditOutcome outcome =
                    service.update(session, teacher(), anEdit(1, ImageAction.KEEP, null));

            assertThat(outcome.status()).isEqualTo(QuestionService.EditStatus.NOT_FOUND);
            assertThat(outcome.detail()).isNull();
            verify(session, never()).persist(any());
        }

        @Test
        @DisplayName("a course she does not teach is NOT_FOUND, never FORBIDDEN")
        void aCourseSheDoesNotTeachIsNotFound() {
            // The existence oracle. A FORBIDDEN naming the course would tell a caller probing
            // display ids both that the question exists and which course it belongs to, which
            // is the disclosure section 6 folds three conditions into one answer to prevent.
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(false);

            QuestionService.EditOutcome outcome =
                    service.update(session, teacher(), anEdit(1, ImageAction.KEEP, null));

            assertThat(outcome.status()).isEqualTo(QuestionService.EditStatus.NOT_FOUND);
            verify(session, never()).persist(any());
        }

        @Test
        @DisplayName("a base version behind the latest is STALE and writes nothing")
        void staleBaseVersionIsRefused() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(3, null)));

            QuestionService.EditOutcome outcome =
                    service.update(session, teacher(), anEdit(2, ImageAction.KEEP, null));

            assertThat(outcome.status()).isEqualTo(QuestionService.EditStatus.STALE);
            verify(session, never()).persist(any());
        }

        @Test
        @DisplayName("writes version n+1 and leaves version n exactly as it was")
        void editWritesANewVersionAndLeavesTheOldOneAlone() {
            QuestionVersion previous = aVersion(2, null);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(previous));
            stubDetailLookups();

            QuestionService.EditOutcome outcome =
                    service.update(session, teacher(), anEdit(2, ImageAction.KEEP, null));

            assertThat(outcome.status()).isEqualTo(QuestionService.EditStatus.UPDATED);
            QuestionVersion written = persistedVersion();
            assertThat(written.getVersionNo()).isEqualTo(3);
            assertThat(written.getText()).isEqualTo("What is encapsulation, precisely?");
            assertThat(written.getCorrectAnswer()).isEqualTo((byte) 2);
            assertThat(written.getCreatedAt()).isEqualTo(NOW);

            // ADR-011, and the half that is invisible from the new row: an exam pinned to
            // version 2 must still find version 2 saying what it said when it was approved.
            assertThat(previous.getVersionNo()).isEqualTo(2);
            assertThat(previous.getText()).isEqualTo("What is encapsulation?");
            assertThat(previous.getCorrectAnswer()).isEqualTo((byte) 1);
            assertThat(previous.getCreatedAt()).isEqualTo(EARLIER);

            assertThat(outcome.detail().versionNo()).isEqualTo(3);
            assertThat(outcome.detail().latestVersionNo()).isEqualTo(3);
        }

        @Test
        @DisplayName("KEEP copies the previous version's image into the new one")
        void keepCopiesTheImage() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(2, png)));
            stubDetailLookups();

            service.update(session, teacher(), anEdit(2, ImageAction.KEEP, null));

            // An edit that dropped the blob would silently un-illustrate the question. That is
            // the whole of what this test guards, and content equality is the right assertion
            // for it.
            //
            // It does NOT guard non-aliasing between the two versions, and an earlier version
            // of this test claimed to by adding isNotSameAs against the array above. Planting
            // proved that worthless: removing QuestionVersion.getImage()'s clone left it green,
            // because the constructor's own clone means the new row never holds the test's
            // array whatever the getter does. The property is real and lives on the entity,
            // where both clones are, and it is guarded there by
            // EntityRoundTripTest.imageIsDefensivelyCopied, which mutates the returned array
            // and asserts the row is unchanged. That test does fail when the clone is removed;
            // this one cannot, and should not pretend to.
            assertThat(persistedVersion().getImage()).containsExactly(png);
        }

        @Test
        @DisplayName("REPLACE takes the new bytes")
        void replaceTakesTheNewImage() {
            byte[] old = {(byte) 0x89, 'P', 'N', 'G'};
            byte[] fresh = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(2, old)));
            stubDetailLookups();

            service.update(session, teacher(), anEdit(2, ImageAction.REPLACE, fresh));

            assertThat(persistedVersion().getImage()).containsExactly(fresh);
        }

        @Test
        @DisplayName("REMOVE clears it, which a null image alone could not express")
        void removeClearsTheImage() {
            byte[] old = {(byte) 0x89, 'P', 'N', 'G'};
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(2, old)));
            stubDetailLookups();

            service.update(session, teacher(), anEdit(2, ImageAction.REMOVE, null));

            assertThat(persistedVersion().getImage()).isNull();
            assertThat(persistedVersion().hasImage()).isFalse();
        }
    }

    // ===================== delete =========================================

    @Nested
    @DisplayName("delete")
    class Delete {

        private QuestionDeleteRequest ask(int baseVersionNo) {
            return new QuestionDeleteRequest(DISPLAY_ID, baseVersionNo);
        }

        @Test
        @DisplayName("an unknown display id is NOT_FOUND")
        void unknownIsNotFound() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID)).thenReturn(Optional.empty());

            QuestionService.DeleteResolution resolved =
                    service.delete(session, teacher(), ask(1));

            assertThat(resolved.status()).isEqualTo(QuestionService.DeleteStatus.NOT_FOUND);
            assertThat(resolved.outcome()).isNull();
        }

        @Test
        @DisplayName("a course she does not teach is NOT_FOUND, never FORBIDDEN")
        void outOfScopeIsNotFound() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(false);

            QuestionService.DeleteResolution resolved =
                    service.delete(session, teacher(), ask(1));

            assertThat(resolved.status()).isEqualTo(QuestionService.DeleteStatus.NOT_FOUND);
            verify(questions, never()).findReferencingExams(any(), org.mockito.ArgumentMatchers
                    .anyLong());
        }

        @Test
        @DisplayName("a stale base version is refused before the blocking query runs")
        void staleIsRefused() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion()));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(3, null)));

            QuestionService.DeleteResolution resolved =
                    service.delete(session, teacher(), ask(2));

            assertThat(resolved.status()).isEqualTo(QuestionService.DeleteStatus.STALE);
            verify(questions, never()).findReferencingExams(any(), org.mockito.ArgumentMatchers
                    .anyLong());
        }

        @Test
        @DisplayName("a referenced question is refused, named, and left exactly where it was")
        void blockedDeleteLeavesTheQuestionAlone() {
            Question question = aQuestion();
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(question));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(2, null)));
            when(questions.findReferencingExams(session, QUESTION_ID)).thenReturn(List.of(
                    new ReferencingExam("101101", "Algebra Midterm"),
                    new ReferencingExam("101102", "Algebra Final")));

            QuestionService.DeleteResolution resolved =
                    service.delete(session, teacher(), ask(2));

            assertThat(resolved.status()).isEqualTo(QuestionService.DeleteStatus.RESOLVED);
            DeleteOutcome outcome = resolved.outcome();
            assertThat(outcome.deleted()).isFalse();
            assertThat(outcome.isBlocked()).isTrue();
            assertThat(outcome.blockingExams())
                    .extracting(exam -> exam.displayId6() + " " + exam.name())
                    .containsExactly("101101 Algebra Midterm", "101102 Algebra Final");

            // The rule with no database backstop. No foreign key fires on an UPDATE, so a
            // stamp written here would remove a question that two exams still pin, and
            // nothing underneath would object.
            assertThat(question.getDeletedAt()).isNull();
            assertThat(question.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("an unreferenced question is soft-deleted, never removed")
        void softDeletes() {
            Question question = aQuestion();
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(question));
            teaches(true);
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(2, null)));
            when(questions.findReferencingExams(session, QUESTION_ID)).thenReturn(List.of());

            QuestionService.DeleteResolution resolved =
                    service.delete(session, teacher(), ask(2));

            assertThat(resolved.status()).isEqualTo(QuestionService.DeleteStatus.RESOLVED);
            assertThat(resolved.outcome().deleted()).isTrue();
            assertThat(resolved.outcome().blockingExams()).isEmpty();
            assertThat(question.getDeletedAt()).isEqualTo(NOW);

            // F2.5 and T-2.8: the row stays, so the version history survives and the serial
            // is never handed out again.
            verify(session, never()).remove(any());
        }
    }
}
