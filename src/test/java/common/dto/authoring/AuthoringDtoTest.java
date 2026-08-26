package common.dto.authoring;

import common.dto.approval.ApprovalState;
import common.dto.bank.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round trips, normalisation and defensive copies for the E7 exam builder wire (E7.9).
 *
 * <p>These are records, so deserialisation runs the compact constructor again on the receiving
 * side. Round-tripping rather than asserting on freshly built values is the whole point: a
 * defensive copy or a blank-to-null rule that only holds locally is a bug that first appears once
 * the two JARs are on two machines, which is where nobody wants to find it.
 *
 * <p>The suite is organised around the one distinction the package is built on and that reviews
 * have burned us on before: <b>inbound records normalise and never throw, outbound records
 * null-check aggressively</b>. Both halves are asserted, because "the constructor does not
 * throw" is only a guarantee if something proves it — E1.11's lesson was that a throwing
 * constructor on an inbound payload kills the socket rather than answering a sentence.
 *
 * <p>Hebrew content is in every text assertion for the same reason it is in the other DTO
 * suites: the product is Hebrew-first, and a serialisation bug that only bites on non-ASCII is
 * one that survives every English fixture.
 */
class AuthoringDtoTest {

    private static final Instant WHEN = Instant.parse("2026-08-23T09:15:00Z");
    private static final String NAME = "מבחן אמצע סמסטר באלגברה";
    private static final String STUDENT_TEXT = "קראו את השאלות בעיון. הצלחה!";
    private static final String TEACHER_TEXT = "בדיקה ידנית לשאלה 7 בלבד.";

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static ComposedQuestion question(int ord, int points, int pinned, int latest) {
        long latestId = latest == pinned ? 4000L + ord : 9000L + ord;
        return new ComposedQuestion(4000L + ord, "1100" + ord, ord, points,
                "כמה צלעות יש למשושה משוכלל?", "גאומטריה", Difficulty.MEDIUM, false,
                pinned, latest, latestId);
    }

    private static ExamVersionRow versionRow(int versionNo, ApprovalState state, String reason) {
        return new ExamVersionRow(900L + versionNo, versionNo, state, reason, 4, 90, WHEN, 3);
    }

    private static ExamComposition composition(ApprovalState state,
                                               String rejectedReason,
                                               List<ComposedQuestion> questions) {
        return new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 2, state, NAME, 90,
                STUDENT_TEXT, TEACHER_TEXT, "דנה כהן", WHEN, rejectedReason, questions, 4);
    }

    // ===================== the shape constants ===============================

    @Nested
    @DisplayName("the shape constants the validator cites")
    class Constants {

        @Test
        @DisplayName("the metadata constants are the contract's numbers, 480 among them")
        void metadataConstantsAreTheRuledOnes() {
            // Lead's ruling 3 of 2026-08-23: the draft's 600 was cut to 480, because the ceiling
            // exists to catch a typo of 600 for 60 and a ceiling of 600 admits that exact typo.
            // Asserting the number here is what stops it drifting back.
            assertThat(ExamCreateRequest.MAX_NAME_LENGTH).isEqualTo(150);
            assertThat(ExamCreateRequest.MIN_DURATION_MINUTES).isEqualTo(1);
            assertThat(ExamCreateRequest.MAX_DURATION_MINUTES).isEqualTo(480);
            assertThat(ExamCreateRequest.MAX_TEXT_LENGTH).isEqualTo(4000);
            assertThat(ExamCreateRequest.POINTS_TOTAL).isEqualTo(100);
        }

        @Test
        @DisplayName("save carries the same five numbers as create, because one validator cites both")
        void saveMirrorsCreate() {
            // ExamValidator is shared by create and save so the two cannot diverge (E7.8). The
            // constants are aliases rather than second literals; this is the assertion that the
            // aliasing actually holds.
            assertThat(ExamVersionSave.MAX_NAME_LENGTH)
                    .isEqualTo(ExamCreateRequest.MAX_NAME_LENGTH);
            assertThat(ExamVersionSave.MIN_DURATION_MINUTES)
                    .isEqualTo(ExamCreateRequest.MIN_DURATION_MINUTES);
            assertThat(ExamVersionSave.MAX_DURATION_MINUTES)
                    .isEqualTo(ExamCreateRequest.MAX_DURATION_MINUTES);
            assertThat(ExamVersionSave.MAX_TEXT_LENGTH)
                    .isEqualTo(ExamCreateRequest.MAX_TEXT_LENGTH);
            assertThat(ExamVersionSave.POINTS_TOTAL).isEqualTo(ExamCreateRequest.POINTS_TOTAL);
        }

        @Test
        @DisplayName("a pin's points range is the column's, and it is what caps the paper at 100")
        void pinPointsRange() {
            assertThat(QuestionPin.MIN_POINTS).isEqualTo(1);
            assertThat(QuestionPin.MAX_POINTS).isEqualTo(100);
            // The contract's "maximum question count is 100 and it is not a rule": it follows
            // from points >= 1 and a sum of 100, so no separate ceiling exists to disagree.
            assertThat(ExamCreateRequest.POINTS_TOTAL / QuestionPin.MIN_POINTS).isEqualTo(100);
        }
    }

    // ===================== inbound: normalise, never throw ===================

    @Nested
    @DisplayName("inbound requests normalise and never throw")
    class Inbound {

        @Test
        @DisplayName("a create request round-trips every field, Hebrew text included")
        void createRoundTrips() throws Exception {
            ExamCreateRequest original = new ExamCreateRequest("11", NAME, 90,
                    STUDENT_TEXT, TEACHER_TEXT,
                    List.of(new QuestionPin(4001L, 60), new QuestionPin(4002L, 40)));

            ExamCreateRequest restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.name()).isEqualTo(NAME);
            assertThat(restored.studentText()).isEqualTo(STUDENT_TEXT);
            assertThat(restored.teacherText()).isEqualTo(TEACHER_TEXT);
            assertThat(restored.questions()).hasSize(2);
            assertThat(restored.hasStudentText()).isTrue();
            assertThat(restored.hasTeacherText()).isTrue();
        }

        @Test
        @DisplayName("a null question list survives, on both sides of the wire")
        void createSurvivesANullList() throws Exception {
            // E1.11: a throw in here runs on the socket read thread and kills the connection.
            // The teacher must get a VALIDATION sentence from ExamValidator instead.
            ExamCreateRequest restored =
                    roundTrip(new ExamCreateRequest("11", NAME, 90, null, null, null));

            assertThat(restored.questions()).isEmpty();
            assertThat(restored.studentText()).isNull();
            assertThat(restored.teacherText()).isNull();
            assertThat(restored.hasStudentText()).isFalse();
            assertThat(restored.hasTeacherText()).isFalse();
        }

        @Test
        @DisplayName("a NULL ELEMENT in the question list survives construction and the wire")
        void createSurvivesANullElement() throws Exception {
            // The reason the copy is not List.copyOf: that throws on a null element. The pin
            // has to arrive so the validator can name its position in the list.
            List<QuestionPin> withHole = new ArrayList<>();
            withHole.add(new QuestionPin(4001L, 50));
            withHole.add(null);
            withHole.add(new QuestionPin(4002L, 50));

            ExamCreateRequest restored =
                    roundTrip(new ExamCreateRequest("11", NAME, 90, null, null, withHole));

            assertThat(restored.questions()).hasSize(3);
            assertThat(restored.questions().get(1)).isNull();
        }

        @Test
        @DisplayName("the copied list is immutable and is not the caller's")
        void createCopiesDefensively() {
            List<QuestionPin> mutable = new ArrayList<>();
            mutable.add(new QuestionPin(4001L, 100));
            ExamCreateRequest request =
                    new ExamCreateRequest("11", NAME, 90, null, null, mutable);

            mutable.add(new QuestionPin(4002L, 5));

            assertThat(request.questions()).hasSize(1);
            assertThatThrownBy(() -> request.questions().add(new QuestionPin(4003L, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("blank texts fold to null; a blank name stays blank for the validator to name")
        void createFoldsBlanksButKeepsTheName() throws Exception {
            ExamCreateRequest restored = roundTrip(
                    new ExamCreateRequest("11", "   ", 90, "   ", "", List.of()));

            assertThat(restored.studentText()).isNull();
            assertThat(restored.teacherText()).isNull();
            // name is REQUIRED, so it is stripped but not folded: the validator's sentence should
            // say the name is blank, not that it is missing.
            assertThat(restored.name()).isEmpty();
        }

        @Test
        @DisplayName("courseCode is strip()ped and NOT trim()ped: U+2003 goes, U+00A0 stays")
        void courseCodeIsStrippedNotTrimmed() throws Exception {
            // The bank contract's rule, imported verbatim including the limit BankBrowseService
            // measured rather than assumed. courses.code2 is CHAR(2) under a PAD SPACE collation,
            // so a code carrying a Unicode space matches the row in SQL while failing Java
            // equality against the reachable set; trim() cuts only characters at or below U+0020
            // and would leave that gap wide open.
            //
            // U+2003 EM SPACE is the case the rule was actually written for: above U+0020, so
            // trim() keeps it and strip() removes it.
            String emPadded = "\u200311\u2003";
            assertThat(emPadded.trim()).as("trim leaves it").isEqualTo(emPadded);
            assertThat(emPadded.strip()).as("strip removes it").isEqualTo("11");

            ExamCreateRequest restored = roundTrip(
                    new ExamCreateRequest(emPadded, NAME, 90, null, null, List.of()));

            assertThat(restored.courseCode()).isEqualTo("11");

            // The same rule on the auto-composer's course, which is the other verb that carries
            // one and the other place requireTeachesCourse compares against the reachable set.
            assertThat(roundTrip(new AutoComposeRequest(emPadded, List.of())).courseCode())
                    .isEqualTo("11");
        }

        @Test
        @DisplayName("a NON-breaking space survives strip here too, and that fails CLOSED")
        void nonBreakingSpacesSurviveStrip() {
            // Pinned, not fixed, exactly as BankBrowseServiceTest.nonBreakingSpacesSurviveStrip
            // pins it on the read side. String.strip() removes what Character.isWhitespace()
            // accepts, and the non-breaking spaces - U+00A0, U+2007, U+202F - are precisely the
            // ones isWhitespace rejects. So strip covers the breaking Unicode spaces trim()
            // misses, and no more.
            //
            // What survives fails CLOSED: the padded code equals no member of the caller's
            // reachable set, so requireTeachesCourse refuses with FORBIDDEN and nothing is
            // written. The dangerous direction would be a value SQL matches while the guard does
            // not, and this is its opposite. Widening to a full Unicode-space fold changes what a
            // course code is allowed to be, which is a lead decision and not a type-landing one.
            String nbspPadded = "\u00A011\u00A0";

            assertThat(new ExamCreateRequest(nbspPadded, NAME, 90, null, null, List.of())
                    .courseCode())
                    .as("strip does not reach U+00A0, and the refusal downstream is the safe one")
                    .isNotEqualTo("11");
            assertThat(new AutoComposeRequest(nbspPadded, List.of()).courseCode())
                    .isNotEqualTo("11");
        }

        @Test
        @DisplayName("a null courseCode stays null rather than exploding")
        void createToleratesANullCourseCode() {
            ExamCreateRequest request =
                    new ExamCreateRequest(null, null, 0, null, null, null);

            assertThat(request.courseCode()).isNull();
            assertThat(request.name()).isNull();
            assertThat(request.questions()).isEmpty();
        }

        @Test
        @DisplayName("a save round-trips, normalises and tolerates a null element too")
        void saveRoundTripsAndTolerates() throws Exception {
            List<QuestionPin> withHole = new ArrayList<>();
            withHole.add(null);
            ExamVersionSave original = new ExamVersionSave(901L, 4, "  " + NAME + "  ", 90,
                    " " + STUDENT_TEXT, "   ", withHole);

            ExamVersionSave restored = roundTrip(original);

            assertThat(restored.examVersionId()).isEqualTo(901L);
            assertThat(restored.expectedLockVersion()).isEqualTo(4);
            assertThat(restored.name()).isEqualTo(NAME);
            assertThat(restored.studentText()).isEqualTo(STUDENT_TEXT);
            assertThat(restored.teacherText()).isNull();
            assertThat(restored.questions()).containsExactly((QuestionPin) null);
            assertThat(restored.hasStudentText()).isTrue();
            assertThat(restored.hasTeacherText()).isFalse();
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("a save survives a null list, and its copy is immutable")
        void saveSurvivesANullList() {
            ExamVersionSave save =
                    new ExamVersionSave(901L, 0, null, 0, null, null, null);

            assertThat(save.questions()).isEmpty();
            assertThat(save.name()).isNull();
            assertThat(save.hasStudentText()).isFalse();
            assertThat(save.hasTeacherText()).isFalse();
            assertThatThrownBy(() -> save.questions().add(new QuestionPin(1L, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);

            // And the other side of both flags, so "she wrote nothing" and "she wrote something"
            // are each proved rather than assumed on a record the validator reads them from.
            ExamVersionSave written = new ExamVersionSave(901L, 1, NAME, 90,
                    STUDENT_TEXT, TEACHER_TEXT, List.of(new QuestionPin(4001L, 100)));

            assertThat(written.hasStudentText()).isTrue();
            assertThat(written.hasTeacherText()).isTrue();
        }

        @Test
        @DisplayName("a pin carries only the version and the points, and round-trips by value")
        void pinRoundTrips() throws Exception {
            QuestionPin original = new QuestionPin(4001L, 25);

            assertThat(roundTrip(original)).isEqualTo(original);
            assertThat(new QuestionPin(4001L, 25)).isEqualTo(original)
                    .hasSameHashCodeAs(original);
            // ord is the list index and questionId is derived server-side: neither is a field.
            assertThat(Arrays.stream(QuestionPin.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName))
                    .containsExactly("questionVersionId", "points");
        }

        @Test
        @DisplayName("the two id-only payloads round-trip and compare by value")
        void idPayloadsRoundTrip() throws Exception {
            ExamVersionRequest request = new ExamVersionRequest(901L);
            ExamVersionAction action = new ExamVersionAction(901L, 4);

            assertThat(roundTrip(request)).isEqualTo(request);
            assertThat(roundTrip(action)).isEqualTo(action);
            assertThat(action.examVersionId()).isEqualTo(901L);
            assertThat(action.expectedLockVersion()).isEqualTo(4);
            // One record for REVISE and SUBMIT both, because they take the same two facts.
            assertThat(new ExamVersionAction(901L, 4)).isEqualTo(action);
            assertThat(new ExamVersionAction(901L, 5)).isNotEqualTo(action);
        }
    }

    // ===================== the criteria grid =================================

    @Nested
    @DisplayName("the auto-compose criteria")
    class Criteria {

        @Test
        @DisplayName("a quota round-trips and derives its own total")
        void quotaRoundTrips() throws Exception {
            TopicQuota original = new TopicQuota("רקורסיה", 2, 3, 1, 4);

            TopicQuota restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.topic()).isEqualTo("רקורסיה");
            assertThat(restored.total()).isEqualTo(10);
            assertThat(restored.isEmpty()).isFalse();
            assertThat(restored.isCourseWide()).isFalse();
        }

        @Test
        @DisplayName("a blank topic folds to null, so 'any topic' has one representation")
        void blankTopicIsCourseWide() throws Exception {
            TopicQuota restored = roundTrip(new TopicQuota("    ", 0, 0, 0, 10));

            assertThat(restored.topic()).isNull();
            assertThat(restored.isCourseWide()).isTrue();
            // And it is the SAME quota as the one built with an explicit null, which is what
            // makes the validator's distinctness rule checkable at all.
            assertThat(restored).isEqualTo(new TopicQuota(null, 0, 0, 0, 10));
        }

        @Test
        @DisplayName("the mixed-difficulty factory is T-3.4's row")
        void mixedDifficultyFactory() {
            TopicQuota quota = TopicQuota.ofAnyDifficulty("אלגברה", 6);

            assertThat(quota.any()).isEqualTo(6);
            assertThat(quota.easy()).isZero();
            assertThat(quota.medium()).isZero();
            assertThat(quota.hard()).isZero();
            assertThat(quota.total()).isEqualTo(6);
        }

        @Test
        @DisplayName("an empty quota is expressible, because refusing it is the validator's job")
        void anEmptyQuotaIsExpressible() {
            TopicQuota quota = new TopicQuota("אלגברה", 0, 0, 0, 0);

            assertThat(quota.isEmpty()).isTrue();
            assertThat(quota.total()).isZero();
        }

        @Test
        @DisplayName("there is no total field: the grid derives it and cannot contradict itself")
        void thereIsNoTotalField() {
            assertThat(Arrays.stream(TopicQuota.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName))
                    .containsExactly("topic", "easy", "medium", "hard", "any");
        }

        @Test
        @DisplayName("a request round-trips its grid and its seed")
        void requestRoundTrips() throws Exception {
            AutoComposeRequest original = new AutoComposeRequest("11",
                    List.of(new TopicQuota("אלגברה", 5, 5, 5, 0),
                            TopicQuota.ofAnyDifficulty("רקורסיה", 5)),
                    424242L);

            AutoComposeRequest restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.hasSeed()).isTrue();
            assertThat(restored.seed()).isEqualTo(424242L);
            assertThat(restored.totalRequested()).isEqualTo(20);
        }

        @Test
        @DisplayName("the two-argument form is what the real client sends: no seed")
        void theUnseededFormIsTheRealOne() throws Exception {
            AutoComposeRequest restored = roundTrip(new AutoComposeRequest("11",
                    List.of(TopicQuota.ofAnyDifficulty("רקורסיה", 3))));

            assertThat(restored.seed()).isNull();
            assertThat(restored.hasSeed()).isFalse();
            assertThat(restored.totalRequested()).isEqualTo(3);
        }

        @Test
        @DisplayName("a null grid and a null quota inside one both survive the wire")
        void requestToleratesNulls() throws Exception {
            assertThat(roundTrip(new AutoComposeRequest("11", null)).quotas()).isEmpty();

            List<TopicQuota> withHole = new ArrayList<>();
            withHole.add(TopicQuota.ofAnyDifficulty("אלגברה", 4));
            withHole.add(null);

            AutoComposeRequest restored = roundTrip(new AutoComposeRequest("11", withHole, null));

            assertThat(restored.quotas()).hasSize(2);
            assertThat(restored.quotas().get(1)).isNull();
            // totalRequested must not be the place a malformed payload explodes.
            assertThat(restored.totalRequested()).isEqualTo(4);
        }

        @Test
        @DisplayName("the request's courseCode is stripped, and its copy is immutable")
        void requestNormalisesAndCopies() {
            AutoComposeRequest request = new AutoComposeRequest(" 11 ", List.of());

            assertThat(request.courseCode()).isEqualTo("11");
            assertThat(request.totalRequested()).isZero();
            assertThatThrownBy(() -> request.quotas().add(TopicQuota.ofAnyDifficulty("x", 1)))
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThat(new AutoComposeRequest(null, List.of()).courseCode()).isNull();
        }
    }

    // ===================== outbound: null-check aggressively =================

    @Nested
    @DisplayName("outbound payloads null-check what the server always knows")
    class Outbound {

        @Test
        @DisplayName("a composed question round-trips, key-free, and carries E7.7's comparison")
        void composedQuestionRoundTrips() throws Exception {
            ComposedQuestion original = question(1, 25, 2, 5);

            ComposedQuestion restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.ord()).isEqualTo(1);
            assertThat(restored.hasNewerVersion()).isTrue();
            assertThat(question(2, 25, 5, 5).hasNewerVersion()).isFalse();
        }

        @Test
        @DisplayName("a composed question has NOWHERE to put an answer or a key")
        void composedQuestionHasNoKey() {
            // The contract's section 9 claim, asserted rather than remembered: E7 adds no type
            // to the correctness boundary, so common.dto.WireDtoLeakGuardTest's licence list
            // does not grow. This is the local, readable half of that guarantee.
            //
            // The exact list is the point. It caught latestVersionId on 2026-08-26 and made
            // adding it a reviewable act rather than a slip, which is what an exact list buys
            // over a "contains no key" scan: the scan below would have passed silently on any
            // component whose name happened to avoid three words. Growing this list is allowed
            // and is meant to cost an argument - the licence for this one is EXAM_BUILDER §4
            // amendment A1 and the reason it carries no key is that a version id is an address,
            // not an answer, exactly as questionVersionId beside it has always been.
            List<String> components = Arrays.stream(ComposedQuestion.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList();

            assertThat(components).containsExactly("questionVersionId", "questionDisplayId5",
                    "ord", "points", "text", "topic", "difficulty", "hasImage",
                    "pinnedVersionNo", "latestVersionNo", "latestVersionId");
            assertThat(components)
                    .as("no answers, no key: the builder is not a preview of the paper")
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("answer")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("correct")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("key"));
        }

        @Test
        @DisplayName("a composed question refuses every null the server should never send")
        void composedQuestionRefusesNulls() {
            assertThatThrownBy(() -> new ComposedQuestion(1L, null, 1, 10, "t", "topic",
                    Difficulty.EASY, false, 1, 1, 1L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("questionDisplayId5");
            assertThatThrownBy(() -> new ComposedQuestion(1L, "11001", 1, 10, null, "topic",
                    Difficulty.EASY, false, 1, 1, 1L))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("text");
            assertThatThrownBy(() -> new ComposedQuestion(1L, "11001", 1, 10, "t", null,
                    Difficulty.EASY, false, 1, 1, 1L))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("topic");
            assertThatThrownBy(() -> new ComposedQuestion(1L, "11001", 1, 10, "t", "topic",
                    null, false, 1, 1, 1L))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("difficulty");
        }

        /**
         * E7.7 draws the badge, E7.14 presses it, and they need different fields ⚑.
         *
         * <p>{@code latestVersionId} landed 2026-08-26 (EXAM_BUILDER §4 A1) because the two
         * version <em>numbers</em> are enough to say the bank has moved on and not enough to say
         * what to move to: {@link QuestionPin} keys on an id. This pins that the id really
         * travels, rather than being computed on one side of the wire.
         */
        @Test
        @DisplayName("⚑ a superseded question carries the id of what superseded it, not just a number")
        void latestVersionIdTravels() throws Exception {
            ComposedQuestion pinnedAtOne = new ComposedQuestion(4001L, "11001", 1, 10, "שאלה",
                    "גאומטריה", Difficulty.EASY, false, 1, 3, 4009L);

            ComposedQuestion back = roundTrip(pinnedAtOne);

            assertThat(back.hasNewerVersion()).isTrue();
            assertThat(back.latestVersionId())
                    .as("the row E7.14 re-pins to, which no comparison of version numbers yields")
                    .isEqualTo(4009L);
            assertThat(back.questionVersionId())
                    .as("and the pin itself has not moved: an exam does not change under her")
                    .isEqualTo(4001L);
        }

        @Test
        @DisplayName("an up-to-date question is its own latest, so re-pinning it is a no-op")
        void anUpToDateQuestionIsItsOwnLatest() {
            ComposedQuestion current = new ComposedQuestion(4001L, "11001", 1, 10, "שאלה",
                    "גאומטריה", Difficulty.EASY, false, 2, 2, 4001L);

            assertThat(current.hasNewerVersion()).isFalse();
            assertThat(current.latestVersionId()).isEqualTo(current.questionVersionId());
        }

        /**
         * The two halves of E7.7 must describe one row ⚑.
         *
         * <p>{@code uq_question_versions_no} makes (question, versionNo) unique, so "the latest
         * number is the pinned number" and "the latest id is the pinned id" are one statement. A
         * row where they disagree has resolved the id by a different rule than the number, which
         * is exactly what {@code max(id)} instead of "the id AT max(versionNo)" would do: right
         * until it is not, and the symptom is an update action re-pinning a question to the wrong
         * version of itself, with no points rule, duplicate rule or constraint to catch it.
         */
        @Test
        @DisplayName("⚑ a latest id that disagrees with the latest number is refused outright")
        void aDisagreeingLatestIdIsRefused() {
            assertThatThrownBy(() -> new ComposedQuestion(4001L, "11001", 1, 10, "t", "topic",
                    Difficulty.EASY, false, 2, 2, 4009L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the same version cannot have two ids");
        }

        @ParameterizedTest
        @EnumSource(Difficulty.class)
        @DisplayName("every bank difficulty survives a round-trip on a composed question")
        void everyDifficultyRoundTrips(Difficulty difficulty) throws Exception {
            ComposedQuestion original = new ComposedQuestion(4001L, "11001", 1, 10, "שאלה",
                    "גאומטריה", difficulty, true, 1, 1, 4001L);

            assertThat(roundTrip(original).difficulty()).isEqualTo(difficulty);
        }

        @ParameterizedTest
        @EnumSource(ApprovalState.class)
        @DisplayName("every approval state survives a round-trip on a version row")
        void everyStateRoundTrips(ApprovalState state) throws Exception {
            ExamVersionRow original = versionRow(2, state, "");

            ExamVersionRow restored = roundTrip(original);

            assertThat(restored.state()).isEqualTo(state);
            assertThat(restored.isEditable()).isEqualTo(state == ApprovalState.DRAFT);
        }

        @Test
        @DisplayName("a rejected version carries its reason ON the row, and '' is its empty")
        void rejectedReasonIsOnTheRow() throws Exception {
            // F4.2: the reason has to be visible on the exam, which a dismissed notification
            // cannot provide. "" and never null, so a screen never guesses which empty it sees.
            ExamVersionRow rejected = roundTrip(
                    versionRow(3, ApprovalState.REJECTED, "חסרה שאלה בנושא רקורסיה"));

            assertThat(rejected.hasRejectedReason()).isTrue();
            assertThat(rejected.rejectedReason()).isEqualTo("חסרה שאלה בנושא רקורסיה");
            assertThat(versionRow(2, ApprovalState.APPROVED, "").hasRejectedReason()).isFalse();

            assertThatThrownBy(() -> versionRow(2, ApprovalState.DRAFT, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rejectedReason");
            assertThatThrownBy(() -> new ExamVersionRow(1L, 1, null, "", 1, 1, WHEN, 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("state");
            assertThatThrownBy(() ->
                    new ExamVersionRow(1L, 1, ApprovalState.DRAFT, "", 1, 1, null, 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("createdAt");
        }

        @Test
        @DisplayName("an exam row round-trips, counts its versions and names the latest")
        void examListRowRoundTrips() throws Exception {
            ExamListRow original = new ExamListRow(77L, "100077", "11", "אלגברה", NAME, 3,
                    List.of(versionRow(3, ApprovalState.DRAFT, ""),
                            versionRow(2, ApprovalState.REJECTED, "נדחה"),
                            versionRow(1, ApprovalState.APPROVED, "")));

            ExamListRow restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.versionCount()).isEqualTo(3);
            assertThat(restored.latestVersion().versionNo()).isEqualTo(3);
            assertThat(restored.name()).isEqualTo(NAME);
        }

        @Test
        @DisplayName("an exam row with no versions answers null rather than indexing into nothing")
        void examListRowWithNoVersions() {
            ExamListRow row =
                    new ExamListRow(77L, "100077", "11", "אלגברה", NAME, 0, List.of());

            assertThat(row.latestVersion()).isNull();
            assertThat(row.versionCount()).isZero();
        }

        @Test
        @DisplayName("an exam row refuses nulls, list elements included")
        void examListRowRefusesNulls() {
            List<ExamVersionRow> withHole = new ArrayList<>();
            withHole.add(null);

            assertThatThrownBy(() ->
                    new ExamListRow(1L, null, "11", "אלגברה", NAME, 1, List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("displayId6");
            assertThatThrownBy(() ->
                    new ExamListRow(1L, "100077", null, "אלגברה", NAME, 1, List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("courseCode");
            assertThatThrownBy(() ->
                    new ExamListRow(1L, "100077", "11", null, NAME, 1, List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("courseName");
            assertThatThrownBy(() ->
                    new ExamListRow(1L, "100077", "11", "אלגברה", null, 1, List.of()))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("name");
            assertThatThrownBy(() ->
                    new ExamListRow(1L, "100077", "11", "אלגברה", NAME, 1, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("versions");
            // Outbound uses the STRICT copy: a null element here is a defect in the assembler,
            // not a payload to be refused politely.
            assertThatThrownBy(() ->
                    new ExamListRow(1L, "100077", "11", "אלגברה", NAME, 1, withHole))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the exam list round-trips, and its empty is a panel rather than an error")
        void examListRoundTrips() throws Exception {
            ExamList original = new ExamList(List.of(
                    new ExamListRow(77L, "100077", "11", "אלגברה", NAME, 1,
                            List.of(versionRow(1, ApprovalState.DRAFT, "")))));

            ExamList restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.rowCount()).isEqualTo(1);
            assertThat(restored.isEmpty()).isFalse();

            ExamList empty = roundTrip(ExamList.empty());
            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.rowCount()).isZero();
        }

        @Test
        @DisplayName("the exam list refuses a null list and a null row")
        void examListRefusesNulls() {
            List<ExamListRow> withHole = new ArrayList<>();
            withHole.add(null);

            assertThatThrownBy(() -> new ExamList(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("rows");
            assertThatThrownBy(() -> new ExamList(withHole))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ===================== the one payload every write answers with ==========

    @Nested
    @DisplayName("the composition, which every writing verb answers with")
    class Composition {

        @Test
        @DisplayName("it round-trips whole, Hebrew metadata and composition together")
        void compositionRoundTrips() throws Exception {
            ExamComposition original = composition(ApprovalState.DRAFT, "",
                    List.of(question(1, 34, 1, 1), question(2, 33, 1, 1),
                            question(3, 33, 2, 4)));

            ExamComposition restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.name()).isEqualTo(NAME);
            assertThat(restored.studentText()).isEqualTo(STUDENT_TEXT);
            assertThat(restored.teacherText()).isEqualTo(TEACHER_TEXT);
            assertThat(restored.authorName()).isEqualTo("דנה כהן");
            assertThat(restored.createdAt()).isEqualTo(WHEN);
            assertThat(restored.questionCount()).isEqualTo(3);
            assertThat(restored.lockVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("its points total 100, which is the invariant section 1 is arranged around")
        void storedCompositionsTotalOneHundred() {
            // 34, 33, 33 is section 7.4's even distribution with the remainder on the earliest
            // questions, and it is what makes an auto-composed proposal savable in one click.
            ExamComposition composition = composition(ApprovalState.APPROVED, "",
                    List.of(question(1, 34, 1, 1), question(2, 33, 1, 1),
                            question(3, 33, 1, 1)));

            assertThat(composition.totalPoints()).isEqualTo(ExamCreateRequest.POINTS_TOTAL);
        }

        @Test
        @DisplayName("only a DRAFT is editable, and the client reads that from one place")
        void onlyADraftIsEditable() {
            assertThat(composition(ApprovalState.DRAFT, "", List.of()).isEditable()).isTrue();
            assertThat(composition(ApprovalState.PENDING, "", List.of()).isEditable()).isFalse();
            assertThat(composition(ApprovalState.APPROVED, "", List.of()).isEditable()).isFalse();
            assertThat(composition(ApprovalState.REJECTED, "נדחה", List.of()).isEditable())
                    .isFalse();
        }

        @Test
        @DisplayName("the rejection reason and the stale-question banner are each one decision")
        void derivedFlags() {
            ExamComposition rejected = composition(ApprovalState.REJECTED, "חסרה שאלה",
                    List.of(question(1, 100, 2, 7)));

            assertThat(rejected.hasRejectedReason()).isTrue();
            assertThat(rejected.hasStaleQuestion()).isTrue();

            ExamComposition fresh = composition(ApprovalState.APPROVED, "",
                    List.of(question(1, 100, 3, 3)));

            assertThat(fresh.hasRejectedReason()).isFalse();
            assertThat(fresh.hasStaleQuestion()).isFalse();
            assertThat(fresh.totalPoints()).isEqualTo(100);
        }

        @Test
        @DisplayName("its two optional texts may be null, and nothing else may")
        void compositionNullRules() throws Exception {
            ExamComposition textless = roundTrip(new ExamComposition(77L, "100077", "11",
                    "אלגברה", 901L, 1, ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן",
                    WHEN, "", List.of(), 0));

            assertThat(textless.studentText()).isNull();
            assertThat(textless.teacherText()).isNull();

            assertThatThrownBy(() -> new ExamComposition(77L, null, "11", "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן", WHEN, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("displayId6");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", null, "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן", WHEN, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("courseCode");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", null, 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן", WHEN, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("courseName");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 1,
                    null, NAME, 90, null, null, "דנה כהן", WHEN, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("state");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, null, 90, null, null, "דנה כהן", WHEN, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("name");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, null, WHEN, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("authorName");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן", null, "", List.of(), 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("createdAt");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן", WHEN, null,
                    List.of(), 0))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rejectedReason");
            assertThatThrownBy(() -> new ExamComposition(77L, "100077", "11", "אלגברה", 901L, 1,
                    ApprovalState.DRAFT, NAME, 90, null, null, "דנה כהן", WHEN, "", null, 0))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("questions");
        }

        @Test
        @DisplayName("its question list is a strict, immutable copy")
        void compositionCopiesStrictly() {
            List<ComposedQuestion> mutable = new ArrayList<>();
            mutable.add(question(1, 100, 1, 1));
            ExamComposition composition =
                    composition(ApprovalState.DRAFT, "", mutable);

            mutable.add(question(2, 5, 1, 1));

            assertThat(composition.questionCount()).isEqualTo(1);
            assertThatThrownBy(() -> composition.questions().add(question(3, 1, 1, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);

            List<ComposedQuestion> withHole = new ArrayList<>();
            withHole.add(null);
            assertThatThrownBy(() -> composition(ApprovalState.DRAFT, "", withHole))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ===================== the reused enums ==================================

    @Nested
    @DisplayName("the two reused enums")
    class ReusedEnums {

        @Test
        @DisplayName("this package declares no enum of its own: Difficulty and ApprovalState are reused")
        void noEnumIsRedeclared() {
            // Contract section 4. A second Difficulty would be two wire types for one concept and
            // the first mismatch would be silent; an ExamState beside ApprovalState would be a
            // second bridge over exam_versions.status with none of E8's exhaustive-switch safety.
            assertThat(ComposedQuestion.class.getRecordComponents()[6].getType())
                    .isEqualTo(common.dto.bank.Difficulty.class);
            assertThat(Shortfall.class.getRecordComponents()[1].getType())
                    .isEqualTo(common.dto.bank.Difficulty.class);
            assertThat(ExamVersionRow.class.getRecordComponents()[2].getType())
                    .isEqualTo(common.dto.approval.ApprovalState.class);
            assertThat(ExamComposition.class.getRecordComponents()[6].getType())
                    .isEqualTo(common.dto.approval.ApprovalState.class);
        }
    }
}
