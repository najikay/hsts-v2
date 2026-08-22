package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.VersionHistory;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.CallerContext;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.BankQuestionSummary;
import server.db.repos.BankQuery;
import server.db.repos.CourseRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BankBrowseService} - the four bank reads and the scope they share (E6.3, E6.5, E6.6).
 *
 * <p>Three properties carry this class, and the happy paths are the least of them.
 *
 * <ul>
 *   <li><b>One reachable set, shared.</b> Contract section 3 requires the browse filter and the
 *       single-question guard to agree by using the same query rather than restating the rule.
 *       {@code TheReachableSet} asserts the union is both correct and computed once.</li>
 *   <li><b>The principal is a branch, not a big set.</b> She is in no course row, so a union over
 *       those tables answers "nothing" for the one caller entitled to everything.</li>
 *   <li><b>Every miss is the same miss.</b> Unknown, soft-deleted and out-of-reach must all be an
 *       empty {@link Optional}, because the handler renders every empty identically and anything
 *       that told them apart here would surface as an oracle there.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BankBrowseServiceTest {

    private static final long TEACHER_ID = 3;
    private static final long PRINCIPAL_ID = 900;
    private static final long QUESTION_ID = 4001;
    private static final String COURSE = "11";
    private static final String OTHER_COURSE = "22";
    private static final String DISPLAY_ID = "11007";
    private static final List<String> ANSWERS =
            List.of("Encapsulation", "Inheritance", "Polymorphism", "Abstraction");
    private static final Instant EARLIER = Instant.parse("2026-08-21T20:00:00Z");

    @Mock
    private Session session;
    @Mock
    private QuestionRepository questions;
    @Mock
    private CourseRepository courses;
    @Mock
    private UserRepository users;

    private BankBrowseService browse;

    @BeforeEach
    void setUp() {
        browse = new BankBrowseService(questions, courses, users);
    }

    // ===================== Fixtures =======================================

    private static CallerContext teacher() {
        return CallerContext.authenticated(null, TEACHER_ID, Role.TEACHER);
    }

    private static CallerContext principal() {
        return CallerContext.authenticated(null, PRINCIPAL_ID, Role.PRINCIPAL);
    }

    private static Question aQuestion(String courseCode) {
        Question question = new Question(courseCode, (short) 7, DISPLAY_ID);
        setId(question, QUESTION_ID);
        return question;
    }

    private static QuestionVersion aVersion(int versionNo, byte[] image) {
        return new QuestionVersion(QUESTION_ID, versionNo, "What is encapsulation?",
                ANSWERS.get(0), ANSWERS.get(1), ANSWERS.get(2), ANSWERS.get(3),
                (byte) 1, "OOP", server.db.entities.Difficulty.MEDIUM,
                image, TEACHER_ID, EARLIER);
    }

    /** Reflection because {@link Question} exposes no id setter; the database assigns it. */
    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not stamp an id on " + entity.getClass(), e);
        }
    }

    private void reaches(String... codes) {
        when(courses.findTaughtCourseCodes(session, TEACHER_ID)).thenReturn(List.of(codes));
        when(courses.findCoordinatedCourseCodes(session, TEACHER_ID)).thenReturn(List.of());
    }

    // ===================== The reachable set ==============================

    @Nested
    @DisplayName("one reachable set, shared by the filter and the guard")
    class TheReachableSet {

        @Test
        @DisplayName("it is the union of what she teaches and what she coordinates")
        void unionOfBothTables() {
            when(courses.findTaughtCourseCodes(session, TEACHER_ID)).thenReturn(List.of("11"));
            when(courses.findCoordinatedCourseCodes(session, TEACHER_ID))
                    .thenReturn(List.of("22", "11"));
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            browse.list(session, teacher(), BankListRequest.firstPage());

            ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
            verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());

            // "11" appears in both tables; a union that let it through twice would put a
            // duplicate in the generated IN list rather than being wrong, which is exactly the
            // kind of harmless-looking thing that stops being harmless in a join.
            assertThat(query.getValue().reachableCourses()).containsExactly("11", "22");
            assertThat(query.getValue().allCourses()).isFalse();
        }

        @Test
        @DisplayName("it is queried once per call, not once per use")
        void computedOnce() {
            reaches(COURSE);
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            browse.list(session, teacher(), BankListRequest.firstPage());

            // countBank and findBankPage both take the query, and the query holds the set. If the
            // scope recomputed per use, this would be two pairs of queries rather than one.
            verify(courses, times(1)).findTaughtCourseCodes(session, TEACHER_ID);
            verify(courses, times(1)).findCoordinatedCourseCodes(session, TEACHER_ID);
        }

        @Test
        @DisplayName("an unauthenticated caller reaches nothing and asks the tables nothing")
        void unauthenticatedReachesNothing() {
            // The handler's role gate makes this unreachable in production, which is the reason
            // to assert it here rather than to skip it: the scope must fail closed on its own
            // terms, so that it stays safe if it is ever reused behind a different gate.
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            BankPage page = browse.list(session, CallerContext.anonymous(null),
                    BankListRequest.firstPage());

            ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
            verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());

            assertThat(query.getValue().allCourses()).isFalse();
            assertThat(query.getValue().reachableCourses()).isEmpty();
            assertThat(page.rows()).isEmpty();
            verify(courses, never()).findTaughtCourseCodes(any(), anyLong());
        }

        @Test
        @DisplayName("the scope asks the database once however often it is asked")
        void asksTheDatabaseOnceHoweverOftenItIsAsked() {
            // The memoization contract section 3 promises. Every current caller happens to ask
            // exactly once, so this property was real code that no test could reach until Scope
            // was made constructible from here. Without this the word "memoizes" in the contract
            // is a sentence in a document and nothing more.
            when(courses.findTaughtCourseCodes(session, TEACHER_ID)).thenReturn(List.of("11"));
            when(courses.findCoordinatedCourseCodes(session, TEACHER_ID)).thenReturn(List.of("22"));

            BankBrowseService.Scope scope = new BankBrowseService.Scope(courses, session);

            Set<String> first = scope.forCaller(teacher());
            Set<String> second = scope.forCaller(teacher());

            assertThat(first).containsExactly("11", "22");
            assertThat(second).isEqualTo(first);
            verify(courses, times(1)).findTaughtCourseCodes(session, TEACHER_ID);
            verify(courses, times(1)).findCoordinatedCourseCodes(session, TEACHER_ID);
        }

        @Test
        @DisplayName("the scope refuses a null caller rather than asking the database about one")
        void aNullCallerReachesNothing() {
            BankBrowseService.Scope scope = new BankBrowseService.Scope(courses, session);

            assertThat(scope.forCaller(null)).isEmpty();
            verify(courses, never()).findTaughtCourseCodes(any(), anyLong());
        }

        @Test
        @DisplayName("what it hands back cannot be edited by whoever receives it")
        void theSetIsNotWritable() {
            // It is handed to Authorization and then to BankQuery. A caller that could add to it
            // would be widening its own scope after the guard had agreed to it.
            when(courses.findTaughtCourseCodes(session, TEACHER_ID)).thenReturn(List.of("11"));
            when(courses.findCoordinatedCourseCodes(session, TEACHER_ID)).thenReturn(List.of());

            Set<String> reachable =
                    new BankBrowseService.Scope(courses, session).forCaller(teacher());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> reachable.add(OTHER_COURSE));
        }

        @Test
        @DisplayName("reaching nothing is an empty page, not an error and not everything")
        void reachingNothingIsAnEmptyPage() {
            // BankQuery distinguishes "reaches nothing" from "reaches everything" precisely so
            // that a scoping bug is an empty screen rather than the whole school's bank. This
            // asserts the service picks the scoped factory, never the unrestricted one.
            reaches();
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            BankPage page = browse.list(session, teacher(), BankListRequest.firstPage());

            ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
            verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());

            assertThat(query.getValue().allCourses()).isFalse();
            assertThat(query.getValue().reachableCourses()).isEmpty();
            assertThat(page.rows()).isEmpty();
            assertThat(page.totalPages()).isZero();
        }
    }

    @Nested
    @DisplayName("the projection becomes a wire row")
    class RowMapping {

        @Test
        @DisplayName("every column lands on the field with the same meaning")
        void everyColumnLandsWhereItBelongs() {
            // Found by reading the coverage gap rather than by chasing a number: every other
            // BANK_LIST test here stubs an empty page, so this mapper had never executed once.
            // Nine values, six of them String, four of those adjacent - a reordering would
            // compile, pass every other test in this file, and put the topic in the course-name
            // column on the teacher's screen.
            reaches(COURSE);
            when(questions.countBank(any(), any())).thenReturn(1L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                    new BankQuestionSummary(DISPLAY_ID, COURSE, "Java", "What is encapsulation?",
                            "OOP", server.db.entities.Difficulty.HARD, 4, true, EARLIER)));

            BankPage page = browse.list(session, teacher(), BankListRequest.firstPage());

            assertThat(page.rows()).hasSize(1);
            BankQuestionRow row = page.rows().get(0);
            assertThat(row.displayId5()).isEqualTo(DISPLAY_ID);
            assertThat(row.courseCode()).isEqualTo(COURSE);
            assertThat(row.courseName()).isEqualTo("Java");
            assertThat(row.text()).isEqualTo("What is encapsulation?");
            assertThat(row.topic()).isEqualTo("OOP");
            assertThat(row.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(row.latestVersionNo()).isEqualTo(4);
            assertThat(row.hasImage()).isTrue();
            assertThat(row.lastVersionAt()).isEqualTo(EARLIER);
        }

        @Test
        @DisplayName("the row carries no answer key, structurally")
        void theRowCannotCarryAKey() {
            // BankQuestionSummary is a scalar projection with nowhere to put correct_answer, so
            // this is a property of the type rather than of the query. Asserted anyway because
            // the bank list is the widest-reaching bank read there is: a coordinator sees every
            // question in her subject on it.
            assertThat(BankQuestionRow.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("correctAnswer", "correct", "answerIndex")
                    .doesNotContain("answers");
        }
    }

    // ===================== The principal ==================================

    @Nested
    @DisplayName("the principal reads every course (F9.3)")
    class ThePrincipal {

        @Test
        @DisplayName("her browse is unrestricted and asks the course tables nothing")
        void browsesEverything() {
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            browse.list(session, principal(), BankListRequest.firstPage());

            ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
            verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());
            assertThat(query.getValue().allCourses()).isTrue();

            // Asking would answer "nothing" for her, since she sits in neither table. A union
            // that ran anyway would be a silent empty screen for the one caller entitled to all
            // of it, so not asking is the assertion.
            verify(courses, never()).findTaughtCourseCodes(any(), anyLong());
            verify(courses, never()).findCoordinatedCourseCodes(any(), anyLong());
        }

        @Test
        @DisplayName("she opens a question in a course she does not teach")
        void readsAnyQuestion() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(OTHER_COURSE)));
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(1, null)));
            when(courses.findName(session, OTHER_COURSE)).thenReturn(Optional.of("History"));
            when(users.findById(session, TEACHER_ID)).thenReturn(Optional.empty());

            Optional<QuestionDetail> detail = browse.get(session, principal(), DISPLAY_ID);

            assertThat(detail).isPresent();
            assertThat(detail.get().courseCode()).isEqualTo(OTHER_COURSE);
            verify(courses, never()).findTaughtCourseCodes(any(), anyLong());
        }
    }

    // ===================== The existence oracle ===========================

    @Nested
    @DisplayName("unknown, deleted and out of reach are one empty answer")
    class TheExistenceOracle {

        @Test
        @DisplayName("a question that does not exist")
        void unknownIsEmpty() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID)).thenReturn(Optional.empty());

            assertThat(browse.get(session, teacher(), DISPLAY_ID)).isEmpty();
            assertThat(browse.versions(session, teacher(), DISPLAY_ID)).isEmpty();
            assertThat(browse.image(session, teacher(), DISPLAY_ID, 1)).isEmpty();
        }

        @Test
        @DisplayName("a question in a course she does not reach, which must look identical")
        void outOfReachIsEmpty() {
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(OTHER_COURSE)));
            reaches(COURSE);

            assertThat(browse.get(session, teacher(), DISPLAY_ID)).isEmpty();

            // The load-bearing half: it must not have read the question's content on the way to
            // refusing. A service that fetched the version first and filtered afterwards would
            // pass the assertion above while doing the work that leaks through timing.
            verify(questions, never()).findLatestVersionForAuthoring(any(), anyLong());
            verify(questions, never()).findVersionsForAuthoring(any(), anyLong());
        }

        @Test
        @DisplayName("a soft-deleted question, which the repository already hides")
        void deletedIsEmpty() {
            // findActiveByDisplayId is where deletion is handled; this pins that the service
            // calls that one rather than findByDisplayId, which would return deleted rows.
            when(questions.findActiveByDisplayId(session, DISPLAY_ID)).thenReturn(Optional.empty());

            assertThat(browse.get(session, teacher(), DISPLAY_ID)).isEmpty();
            verify(questions).findActiveByDisplayId(session, DISPLAY_ID);
            verify(questions, never()).findByDisplayId(any(), anyString());
        }
    }

    // ===================== The reads themselves ===========================

    @Nested
    @DisplayName("what the reads answer when they find something")
    class TheReads {

        @Test
        @DisplayName("QUESTION_GET carries the latest version and says which it is")
        void getCarriesTheLatest() {
            reaches(COURSE);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(COURSE)));
            when(questions.findLatestVersionForAuthoring(session, QUESTION_ID))
                    .thenReturn(Optional.of(aVersion(3, null)));
            when(courses.findName(session, COURSE)).thenReturn(Optional.of("Java"));
            when(users.findById(session, TEACHER_ID)).thenReturn(Optional.empty());

            QuestionDetail detail = browse.get(session, teacher(), DISPLAY_ID).orElseThrow();

            assertThat(detail.versionNo()).isEqualTo(3);
            assertThat(detail.latestVersionNo()).isEqualTo(3);
            assertThat(detail.answers()).containsExactlyElementsOf(ANSWERS);
            assertThat(detail.correctAnswer()).isEqualTo(1);
        }

        @Test
        @DisplayName("QUESTION_VERSIONS keeps the repository's newest-first order")
        void versionsKeepTheOrder() {
            reaches(COURSE);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(COURSE)));
            when(questions.findVersionsForAuthoring(session, QUESTION_ID))
                    .thenReturn(List.of(aVersion(3, null), aVersion(2, null), aVersion(1, null)));
            // No course-name stub: a version detail carries the author and not the course, which
            // lives once on the VersionHistory's question. Mockito's strict stubbing caught the
            // unused stub, which is a small proof that the mapper reads only what it needs.
            when(users.findById(session, TEACHER_ID)).thenReturn(Optional.empty());

            VersionHistory history = browse.versions(session, teacher(), DISPLAY_ID).orElseThrow();

            // The panel is a timeline read top-down and VersionHistory preserves what it is
            // given, so the ordering decided in the query is the ordering the teacher sees.
            assertThat(history.versions()).extracting(v -> v.versionNo()).containsExactly(3, 2, 1);
        }

        @Test
        @DisplayName("a question with no versions is a miss, not an empty timeline")
        void orphanedQuestionIsAMiss() {
            reaches(COURSE);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(COURSE)));
            when(questions.findVersionsForAuthoring(session, QUESTION_ID)).thenReturn(List.of());

            assertThat(browse.versions(session, teacher(), DISPLAY_ID)).isEmpty();
        }

        @Test
        @DisplayName("QUESTION_IMAGE_GET sniffs the type from the bytes it is about to send")
        void imageSniffsItsOwnBytes() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
            reaches(COURSE);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(COURSE)));
            when(questions.findVersionForAuthoring(session, QUESTION_ID, 2))
                    .thenReturn(Optional.of(aVersion(2, png)));

            QuestionImage image = browse.image(session, teacher(), DISPLAY_ID, 2).orElseThrow();

            assertThat(image.contentType()).isEqualTo("image/png");
            assertThat(image.bytes()).isEqualTo(png);
            assertThat(image.versionNo()).isEqualTo(2);
        }

        @Test
        @DisplayName("a version with no picture is a miss rather than an empty image")
        void noImageIsAMiss() {
            reaches(COURSE);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(COURSE)));
            when(questions.findVersionForAuthoring(session, QUESTION_ID, 2))
                    .thenReturn(Optional.of(aVersion(2, null)));

            assertThat(browse.image(session, teacher(), DISPLAY_ID, 2)).isEmpty();
        }

        @Test
        @DisplayName("bytes that are not a picture are a miss, not a mislabelled download")
        void unrecognisedBytesAreAMiss() {
            // The stored blob is validated on the way in, so this should be unreachable. It is
            // asserted anyway because the alternative behaviour, guessing a content type, would
            // hand a browser something to execute rather than render.
            reaches(COURSE);
            when(questions.findActiveByDisplayId(session, DISPLAY_ID))
                    .thenReturn(Optional.of(aQuestion(COURSE)));
            when(questions.findVersionForAuthoring(session, QUESTION_ID, 2))
                    .thenReturn(Optional.of(aVersion(2, "<script>".getBytes())));

            assertThat(browse.image(session, teacher(), DISPLAY_ID, 2)).isEmpty();
        }
    }

    // ===================== Paging =========================================

    @Nested
    @DisplayName("paging is clamped, never trusted")
    class Paging {

        @Test
        @DisplayName("an oversized page is cut to the maximum")
        void oversizeIsClamped() {
            reaches(COURSE);
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            BankPage page = browse.list(session, teacher(),
                    new BankListRequest(null, null, null, null, 0, 100_000));

            assertThat(page.pageSize()).isEqualTo(BankListRequest.MAX_PAGE_SIZE);
            verify(questions).findBankPage(eq(session), any(), eq(0),
                    eq(BankListRequest.MAX_PAGE_SIZE));
        }

        @Test
        @DisplayName("a negative page is the first one, and an unusable size is the default")
        void negativesAreClamped() {
            reaches(COURSE);
            when(questions.countBank(any(), any())).thenReturn(0L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            BankPage page = browse.list(session, teacher(),
                    new BankListRequest(null, null, null, null, -5, 0));

            assertThat(page.page()).isZero();
            assertThat(page.pageSize()).isEqualTo(BankListRequest.DEFAULT_PAGE_SIZE);
            // A negative offset would be an exception from the driver rather than a refusal.
            verify(questions).findBankPage(eq(session), any(), eq(0),
                    eq(BankListRequest.DEFAULT_PAGE_SIZE));
        }

        @Test
        @DisplayName("the pager never offers a last page with nothing on it")
        void totalPagesIsACeiling() {
            reaches(COURSE);
            when(questions.countBank(any(), any())).thenReturn(41L);
            when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

            BankPage page = browse.list(session, teacher(), BankListRequest.firstPage());

            // 41 rows at 40 a page is 2, not 1 and not 2.025 floored to 2 by accident.
            assertThat(page.totalRows()).isEqualTo(41);
            assertThat(page.totalPages()).isEqualTo(2);
        }
    }

    // ===================== The filter is not the scope ====================

    @Test
    @DisplayName("filtering by a course she cannot reach matches nothing and is not a refusal")
    void filterOutsideScopeIsNotARefusal() {
        // Contract section 8: the client's course list is a convenience, not a boundary. The
        // filter and the scope are separate fields on BankQuery and the intersection happens in
        // SQL, so asking for someone else's course is an empty screen rather than an error.
        reaches(COURSE);
        when(questions.countBank(any(), any())).thenReturn(0L);
        when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        BankPage page = browse.list(session, teacher(),
                new BankListRequest(OTHER_COURSE, null, null, null, 0, 40));

        ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
        verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());

        assertThat(query.getValue().courseCode()).isEqualTo(OTHER_COURSE);
        assertThat(query.getValue().reachableCourses()).containsExactly(COURSE);
        assertThat(page.rows()).isEmpty();
    }

    @Test
    @DisplayName("a NON-breaking space survives strip, and the refusal is the safe direction")
    void nonBreakingSpacesSurviveStrip() {
        // Found by planting U+00A0 expecting it to be stripped, and watching this fail.
        // String.strip() removes what Character.isWhitespace() accepts, and the NON-breaking
        // spaces - U+00A0, U+2007, U+202F - are exactly the ones isWhitespace rejects. So strip
        // covers the breaking Unicode spaces that trim() misses, and no more. The javadoc on
        // this rule used to imply it closed the whole gap; it closes most of it.
        //
        // Pinned rather than fixed, because what survives fails CLOSED: the padded code equals
        // no member of the reachable set, so the guard refuses and the filter matches nothing.
        // The dangerous direction would be a value SQL matches while the guard does not, and
        // this is its opposite. Widening to a full Unicode-space fold changes what a course code
        // is allowed to be, which is the lead's call and not a read PR's.
        reaches(COURSE);
        when(questions.countBank(any(), any())).thenReturn(0L);
        when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        browse.list(session, teacher(),
                new BankListRequest(" 11 ", null, null, null, 0, 40));

        ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
        verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());

        assertThat(query.getValue().courseCode()).isNotEqualTo(COURSE);
        assertThat(query.getValue().reachableCourses()).containsExactly(COURSE);
    }

    @Test
    @DisplayName("a course code padded with breaking whitespace is stripped, never trimmed")
    void breakingUnicodeSpacesAreStripped() {
        // U+2003 EM SPACE sits above U+0020, so trim() would leave it and strip() removes it.
        // This is the case the "stripped, not trimmed" rule was actually written for, and it is
        // real.
        //
        // Both this literal and the U+00A0 one above hold the character itself rather than a
        // \\u escape, which is what let the difference between them go unnoticed until a planted
        // assertion failed. If either test ever starts passing for a reason that looks like
        // nothing changed, check these bytes first: they are invisible on screen.
        reaches(COURSE);
        when(questions.countBank(any(), any())).thenReturn(0L);
        when(questions.findBankPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        browse.list(session, teacher(),
                new BankListRequest(" " + COURSE + " ", null, null, null, 0, 40));

        ArgumentCaptor<BankQuery> query = ArgumentCaptor.forClass(BankQuery.class);
        verify(questions).findBankPage(eq(session), query.capture(), anyInt(), anyInt());
        assertThat(query.getValue().courseCode()).isEqualTo(COURSE);
    }
}
