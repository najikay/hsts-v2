package common.dto.bank;

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
 * Round trips, normalisation and defensive copies for the E6 bank wire (E6.8).
 *
 * <p>These are records, so deserialisation runs the compact constructor again on the receiving
 * side. Round-tripping rather than asserting on freshly built values is the whole point: a
 * defensive copy or a null-to-empty rule that only holds locally is a bug that first appears
 * once the two JARs are on two machines, which is where nobody wants to find it.
 *
 * <p>Hebrew content is in every text assertion for the same reason it is in the other DTO
 * suites: the product is Hebrew-first, and a serialisation bug that only bites on non-ASCII is
 * one that survives every English fixture.
 */
class BankDtoTest {

    private static final Instant WHEN = Instant.parse("2026-08-21T09:15:00Z");

    /** Four options in Hebrew, the way a real question in the seed reads. */
    private static final List<String> ANSWERS =
            List.of("ארבע", "שש", "שמונה", "שתים עשרה");

    private static final String STEM = "כמה צלעות יש למשושה משוכלל?";

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

    private static QuestionDetail detail() {
        return new QuestionDetail("11005", "11", "אלגברה", 2, 3, STEM, ANSWERS, 2,
                "גאומטריה", Difficulty.MEDIUM, true, "דנה כהן", WHEN);
    }

    private static BankQuestionRow row(String displayId) {
        return new BankQuestionRow(displayId, "11", "אלגברה", "כמה צלעות...", "גאומטריה",
                Difficulty.EASY, 703L, 3, false, WHEN);
    }

    // ===================== the enums =====================================

    @Nested
    @DisplayName("the wire enums")
    class Enums {

        @ParameterizedTest
        @EnumSource(Difficulty.class)
        @DisplayName("every difficulty survives a round-trip inside a row")
        void everyDifficultyRoundTrips(Difficulty difficulty) throws Exception {
            BankQuestionRow original = new BankQuestionRow("11005", "11", "אלגברה", STEM,
                    "גאומטריה", difficulty, 701L, 1, false, WHEN);

            assertThat(roundTrip(original).difficulty()).isEqualTo(difficulty);
        }

        @Test
        @DisplayName("the wire difficulty names match the entity's, member for member")
        void difficultyMatchesTheEntityEnum() {
            // Two types on purpose (no entity type travels), so the mapping at the service
            // boundary is a valueOf. That only stays true while the names agree, and enums
            // serialize by name, so this is the check that keeps them agreeing.
            List<String> wire = Arrays.stream(Difficulty.values()).map(Enum::name).toList();
            List<String> entity = Arrays.stream(server.db.entities.Difficulty.values())
                    .map(Enum::name).toList();

            assertThat(wire).isEqualTo(entity);
        }

        @ParameterizedTest
        @EnumSource(ImageAction.class)
        @DisplayName("every image action survives a round-trip inside an edit")
        void everyImageActionRoundTrips(ImageAction action) throws Exception {
            QuestionEdit original = new QuestionEdit("11005", 3, STEM, ANSWERS, 1, "גאומטריה",
                    Difficulty.HARD, action, null);

            assertThat(roundTrip(original).imageAction()).isEqualTo(action);
        }

        @Test
        @DisplayName("there are three image actions, because null image is ambiguous")
        void imageActionHasThreeStates() {
            assertThat(ImageAction.values())
                    .containsExactly(ImageAction.KEEP, ImageAction.REPLACE, ImageAction.REMOVE);
        }
    }

    // ===================== listing ========================================

    @Nested
    @DisplayName("listing and filtering")
    class Listing {

        @Test
        @DisplayName("a list request round-trips every filter, Hebrew search included")
        void listRequestRoundTrips() throws Exception {
            BankListRequest original = new BankListRequest("11", "גאומטריה", Difficulty.HARD,
                    "משושה", 2, 40);

            BankListRequest restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.search()).isEqualTo("משושה");
            assertThat(restored.page()).isEqualTo(2);
            assertThat(restored.size()).isEqualTo(40);
        }

        @Test
        @DisplayName("blank filters fold to null on both sides of the wire")
        void blankFiltersFoldToNull() throws Exception {
            BankListRequest restored = roundTrip(
                    new BankListRequest("  ", "", null, "   ", 0, 40));

            assertThat(restored.courseCode()).isNull();
            assertThat(restored.topic()).isNull();
            assertThat(restored.search()).isNull();
            assertThat(restored.isUnfiltered()).isTrue();
        }

        @Test
        @DisplayName("filters are trimmed, so a pasted course code still matches")
        void filtersAreTrimmed() {
            BankListRequest request =
                    new BankListRequest(" 11 ", " גאומטריה ", null, " משושה ", 0, 40);

            assertThat(request.courseCode()).isEqualTo("11");
            assertThat(request.topic()).isEqualTo("גאומטריה");
            assertThat(request.search()).isEqualTo("משושה");
            assertThat(request.isUnfiltered()).isFalse();
        }

        @Test
        @DisplayName("a difficulty alone still counts as filtered")
        void difficultyAloneIsAFilter() {
            assertThat(new BankListRequest(null, null, Difficulty.EASY, null, 0, 40).isUnfiltered())
                    .isFalse();
        }

        @Test
        @DisplayName("the first page asks for the default size and no filters")
        void firstPageIsUnfiltered() {
            BankListRequest first = BankListRequest.firstPage();

            assertThat(first.isUnfiltered()).isTrue();
            assertThat(first.page()).isZero();
            assertThat(first.size()).isEqualTo(BankListRequest.DEFAULT_PAGE_SIZE);
            assertThat(BankListRequest.DEFAULT_PAGE_SIZE)
                    .isBetween(BankListRequest.MIN_PAGE_SIZE, BankListRequest.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("paging keeps the filters, which is what a pager must never lose")
        void onPageKeepsFilters() {
            BankListRequest page3 = new BankListRequest("11", "גאומטריה", Difficulty.HARD,
                    "משושה", 0, 40).onPage(3);

            assertThat(page3.page()).isEqualTo(3);
            assertThat(page3.courseCode()).isEqualTo("11");
            assertThat(page3.topic()).isEqualTo("גאומטריה");
            assertThat(page3.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(page3.search()).isEqualTo("משושה");
            assertThat(page3.size()).isEqualTo(40);
        }

        @Test
        @DisplayName("a page round-trips its rows and its totals")
        void bankPageRoundTrips() throws Exception {
            BankPage original =
                    new BankPage(List.of(row("11005"), row("11006")), 1, 40, 312L, 8);

            BankPage restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.rowCount()).isEqualTo(2);
            assertThat(restored.pageSize()).isEqualTo(40);
            assertThat(restored.totalRows()).isEqualTo(312L);
            assertThat(restored.hasNextPage()).isTrue();
            assertThat(restored.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("the last page reports no next page")
        void lastPageHasNoNext() {
            assertThat(new BankPage(List.of(row("11005")), 7, 40, 312L, 8).hasNextPage()).isFalse();
        }

        @Test
        @DisplayName("an empty bank is an empty state, not a null list")
        void emptyPage() throws Exception {
            BankPage empty = roundTrip(BankPage.empty(40));

            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.rows()).isEmpty();
            assertThat(empty.rowCount()).isZero();
            assertThat(empty.totalRows()).isZero();
            assertThat(empty.hasNextPage()).isFalse();
        }

        @Test
        @DisplayName("a null row list becomes empty, and the copy is immutable")
        void bankPageDefensivelyCopies() throws Exception {
            assertThat(new BankPage(null, 0, 40, 0L, 0).rows()).isEmpty();

            List<BankQuestionRow> mutable = new ArrayList<>(List.of(row("11005")));
            BankPage page = new BankPage(mutable, 0, 40, 1L, 1);
            mutable.add(row("11006"));

            assertThat(page.rowCount()).isEqualTo(1);
            assertThat(roundTrip(page).rowCount()).isEqualTo(1);
            assertThatThrownBy(() -> page.rows().add(row("11007")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a row round-trips, and carries no answers at all")
        void rowRoundTrips() throws Exception {
            BankQuestionRow restored = roundTrip(row("11005"));

            assertThat(restored).isEqualTo(row("11005"));
            assertThat(restored.courseName()).isEqualTo("אלגברה");
            assertThat(restored.lastVersionAt()).isEqualTo(WHEN);
            assertThat(restored.latestVersionNo()).isEqualTo(3);
            assertThat(restored.hasImage()).isFalse();
        }

        @Test
        @DisplayName("the truncation length is one shared constant, not a per-request parameter")
        void stemLengthIsAConstant() {
            // The server cuts and the client renders the cut; two numbers would mean an ellipsis
            // in the wrong place or a tooltip promising text that never travelled.
            assertThat(BankQuestionRow.STEM_PREVIEW_CHARS).isEqualTo(160);
        }

        @Test
        @DisplayName("a row the server could not have built fails where the server can see it")
        void rowNullChecks() {
            assertThatThrownBy(() -> new BankQuestionRow(null, "11", "אלגברה", STEM, "t",
                    Difficulty.EASY, 701L, 1, false, WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("displayId5");
            assertThatThrownBy(() -> new BankQuestionRow("11005", null, "אלגברה", STEM, "t",
                    Difficulty.EASY, 701L, 1, false, WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("courseCode");
            assertThatThrownBy(() -> new BankQuestionRow("11005", "11", "אלגברה", null, "t",
                    Difficulty.EASY, 701L, 1, false, WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("text");
            assertThatThrownBy(() -> new BankQuestionRow("11005", "11", "אלגברה", STEM, "t",
                    null, 7001L, 1, false, WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("difficulty");
        }
    }

    // ===================== the detail =====================================

    @Nested
    @DisplayName("the staff-only detail")
    class Detail {

        @Test
        @DisplayName("a request round-trips its display id")
        void requestRoundTrips() throws Exception {
            assertThat(roundTrip(new QuestionRequest("11005")).displayId5()).isEqualTo("11005");
        }

        @Test
        @DisplayName("a detail round-trips the key and all four Hebrew answers")
        void detailRoundTrips() throws Exception {
            QuestionDetail restored = roundTrip(detail());

            assertThat(restored).isEqualTo(detail());
            assertThat(restored.answers()).containsExactlyElementsOf(ANSWERS);
            assertThat(restored.correctAnswer()).isEqualTo(2);
            assertThat(restored.text()).isEqualTo(STEM);
            assertThat(restored.authorName()).isEqualTo("דנה כהן");
            assertThat(restored.createdAt()).isEqualTo(WHEN);
            assertThat(restored.hasImage()).isTrue();
        }

        @Test
        @DisplayName("it says whether it is the version an edit would branch from")
        void isLatest() {
            assertThat(detail().isLatest()).isFalse();
            assertThat(new QuestionDetail("11005", "11", "אלגברה", 3, 3, STEM, ANSWERS, 1,
                    "גאומטריה", Difficulty.MEDIUM, false, "דנה כהן", WHEN).isLatest()).isTrue();
        }

        @Test
        @DisplayName("options are addressed 1..4 and the key resolves to its text")
        void answerByIndex() throws Exception {
            QuestionDetail restored = roundTrip(detail());

            assertThat(restored.answer(1)).isEqualTo("ארבע");
            assertThat(restored.answer(4)).isEqualTo("שתים עשרה");
            assertThat(restored.correctAnswerText()).isEqualTo("שש");
            assertThat(QuestionDetail.ANSWER_COUNT).isEqualTo(4);
        }

        @Test
        @DisplayName("asking for an option that cannot exist is a programming error, not a null")
        void answerOutOfRange() {
            assertThatThrownBy(() -> detail().answer(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("asked for 0");
            assertThatThrownBy(() -> detail().answer(5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("asked for 5");
        }

        @Test
        @DisplayName("a null answer list is a server bug and says so at build time; the copy is immutable")
        void detailDefensivelyCopies() {
            // Outbound policy corrected 2026-08-21 (Member A's contract read, finding 5): the
            // required, prose-constrained field surfaces as a server-side failure, never as an
            // empty editor whose error names neither the question nor the server.
            assertThatThrownBy(() -> new QuestionDetail("11005", "11", "אלגברה", 1, 1, STEM, null,
                    1, "גאומטריה", Difficulty.EASY, false, "דנה כהן", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("answers");

            List<String> mutable = new ArrayList<>(ANSWERS);
            QuestionDetail detail = new QuestionDetail("11005", "11", "אלגברה", 1, 1, STEM,
                    mutable, 1, "גאומטריה", Difficulty.EASY, false, "דנה כהן", WHEN);
            mutable.add("חמש");

            assertThat(detail.answers()).hasSize(4);
            assertThatThrownBy(() -> detail.answers().add("חמש"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a detail the server could not have built fails where the server can see it")
        void detailNullChecks() {
            assertThatThrownBy(() -> new QuestionDetail(null, "11", "א", 1, 1, STEM, ANSWERS, 1,
                    "t", Difficulty.EASY, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("displayId5");
            assertThatThrownBy(() -> new QuestionDetail("11005", null, "א", 1, 1, STEM, ANSWERS, 1,
                    "t", Difficulty.EASY, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("courseCode");
            assertThatThrownBy(() -> new QuestionDetail("11005", "11", "א", 1, 1, null, ANSWERS, 1,
                    "t", Difficulty.EASY, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("text");
            assertThatThrownBy(() -> new QuestionDetail("11005", "11", "א", 1, 1, STEM, ANSWERS, 1,
                    "t", null, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("difficulty");
        }

        @Test
        @DisplayName("an out-of-range key is the handler's VALIDATION answer, not an exception here")
        void rangeValidationIsNotTheDtosJob() {
            // Package rule, inherited from common.dto.grading: throwing inside a
            // deserialization on a socket read thread turns a client bug into a dropped
            // connection instead of a sentence naming the field.
            QuestionDetail nonsense = new QuestionDetail("11005", "11", "אלגברה", 1, 1, STEM,
                    ANSWERS, 7, "גאומטריה", Difficulty.EASY, false, "דנה כהן", WHEN);

            assertThat(nonsense.correctAnswer()).isEqualTo(7);
        }
    }

    // ===================== version history ================================

    @Nested
    @DisplayName("version history")
    class History {

        private QuestionVersionDetail version(int no) {
            return new QuestionVersionDetail(no, STEM + " v" + no, ANSWERS, 2, "גאומטריה",
                    Difficulty.MEDIUM, false, "דנה כהן", WHEN);
        }

        @Test
        @DisplayName("a version round-trips its wording and its key")
        void versionRoundTrips() throws Exception {
            QuestionVersionDetail restored = roundTrip(version(2));

            assertThat(restored).isEqualTo(version(2));
            assertThat(restored.versionNo()).isEqualTo(2);
            assertThat(restored.correctAnswer()).isEqualTo(2);
            assertThat(restored.answer(2)).isEqualTo("שש");
            assertThat(restored.authorName()).isEqualTo("דנה כהן");
        }

        @Test
        @DisplayName("a version's options are addressed 1..4 too")
        void versionAnswerOutOfRange() {
            assertThat(version(1).answer(1)).isEqualTo("ארבע");
            assertThatThrownBy(() -> version(1).answer(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("asked for 0");
            assertThatThrownBy(() -> version(1).answer(9))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("asked for 9");
        }

        @Test
        @DisplayName("a version the server could not have built fails loudly")
        void versionNullChecks() {
            assertThatThrownBy(() -> new QuestionVersionDetail(1, null, ANSWERS, 1, "t",
                    Difficulty.EASY, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("text");
            assertThatThrownBy(() -> new QuestionVersionDetail(1, STEM, ANSWERS, 1, "t",
                    null, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("difficulty");
            assertThatThrownBy(() -> new QuestionVersionDetail(1, STEM, null, 1, "t",
                    Difficulty.EASY, false, "דנה", WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("answers");
        }

        @Test
        @DisplayName("a history round-trips newest first, current version included")
        void historyRoundTrips() throws Exception {
            VersionHistory original =
                    new VersionHistory("11005", List.of(version(3), version(2), version(1)));

            VersionHistory restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.versions().size()).isEqualTo(3);
            assertThat(restored.isEmpty()).isFalse();
            assertThat(restored.latest()).contains(version(3));
        }

        @Test
        @DisplayName("a history with nothing in it does not blow up on latest()")
        void emptyHistory() {
            VersionHistory empty = new VersionHistory("11005", null);

            assertThat(empty.versions()).isEmpty();
            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.versions().size()).isZero();
            assertThat(empty.latest()).isEmpty();
        }

        @Test
        @DisplayName("the version list is defensively copied and immutable")
        void historyDefensivelyCopies() {
            List<QuestionVersionDetail> mutable = new ArrayList<>(List.of(version(1)));
            VersionHistory history = new VersionHistory("11005", mutable);
            mutable.add(version(2));

            assertThat(history.versions().size()).isEqualTo(1);
            assertThatThrownBy(() -> history.versions().add(version(2)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a history without a question id fails loudly")
        void historyNullChecks() {
            assertThatThrownBy(() -> new VersionHistory(null, List.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("displayId5");
        }
    }

    // ===================== authoring payloads =============================

    @Nested
    @DisplayName("the inbound authoring payloads")
    class Authoring {

        private final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

        private QuestionDraft draft(byte[] image) {
            return new QuestionDraft("11", STEM, ANSWERS, 2, "גאומטריה", Difficulty.MEDIUM, image);
        }

        private QuestionEdit edit(ImageAction action, byte[] image) {
            return new QuestionEdit("11005", 3, STEM, ANSWERS, 2, "גאומטריה", Difficulty.HARD,
                    action, image);
        }

        @Test
        @DisplayName("a draft round-trips, image bytes and Hebrew intact")
        void draftRoundTrips() throws Exception {
            QuestionDraft restored = roundTrip(draft(png));

            assertThat(restored).isEqualTo(draft(png));
            assertThat(restored.hashCode()).isEqualTo(draft(png).hashCode());
            assertThat(restored.image()).containsExactly(png);
            assertThat(restored.hasImage()).isTrue();
            assertThat(restored.text()).isEqualTo(STEM);
            assertThat(restored.answers()).containsExactlyElementsOf(ANSWERS);
            assertThat(restored.correctAnswer()).isEqualTo(2);
        }

        @Test
        @DisplayName("a draft without a picture round-trips a null image, not an empty array")
        void draftWithoutImage() throws Exception {
            QuestionDraft restored = roundTrip(draft(null));

            assertThat(restored.image()).isNull();
            assertThat(restored.hasImage()).isFalse();
            assertThat(restored).isEqualTo(draft(null));
        }

        @Test
        @DisplayName("an empty array is not an illustration")
        void emptyImageIsNoImage() {
            assertThat(draft(new byte[0]).hasImage()).isFalse();
        }

        @Test
        @DisplayName("the image is copied in and out, so no caller can corrupt it")
        void draftCopiesTheImage() {
            byte[] source = png.clone();
            QuestionDraft draft = draft(source);

            source[0] = 0;
            assertThat(draft.image()[0]).isEqualTo((byte) 0x89);

            draft.image()[0] = 0;
            assertThat(draft.image()[0]).isEqualTo((byte) 0x89);
        }

        @Test
        @DisplayName("two drafts built from the same inputs are equal, bytes compared by value")
        void draftComparesByValue() {
            QuestionDraft same = draft(png);
            assertThat(same).isEqualTo(same);
            assertThat(draft(png.clone())).isEqualTo(draft(png.clone()));
            assertThat(draft(png.clone())).hasSameHashCodeAs(draft(png.clone()));
            assertThat(draft(png)).isEqualTo(draft(png));
            assertThat(draft(png)).isNotEqualTo(draft(new byte[]{1, 2, 3}));
            assertThat(draft(png)).isNotEqualTo(draft(null));
            assertThat(draft(png)).isNotEqualTo("not a draft");
            assertThat(draft(png)).isNotEqualTo(
                    new QuestionDraft("12", STEM, ANSWERS, 2, "גאומטריה", Difficulty.MEDIUM, png));
        }

        @Test
        @DisplayName("a draft never prints the key or the picture into a log line")
        void draftToStringIsSafe() {
            String text = draft(png).toString();

            assertThat(text)
                    .contains("courseCode=11")
                    .contains("difficulty=MEDIUM")
                    .contains("8 bytes")
                    .doesNotContain(STEM)
                    .doesNotContain("שש");
            assertThat(draft(null).toString()).contains("image=none");
        }

        @Test
        @DisplayName("a draft's answers are copied, and a null list becomes empty")
        void draftCopiesAnswers() {
            List<String> mutable = new ArrayList<>(ANSWERS);
            QuestionDraft draft = draft(null);
            QuestionDraft fromMutable = new QuestionDraft("11", STEM, mutable, 1, "t",
                    Difficulty.EASY, null);
            mutable.add("חמש");

            assertThat(fromMutable.answers()).hasSize(4);
            assertThat(new QuestionDraft("11", STEM, null, 1, "t", Difficulty.EASY, null).answers())
                    .isEmpty();
            assertThat(draft.answers()).containsExactlyElementsOf(ANSWERS);
        }

        @Test
        @DisplayName("a draft normalises rather than throwing: nulls are the handler's VALIDATION")
        void draftDoesNotThrowOnNulls() {
            // Inbound payloads are deserialized on a socket read thread. A NullPointerException
            // there is a dropped connection; a VALIDATION answer names the field the teacher
            // has to fix.
            QuestionDraft blank = new QuestionDraft(null, null, null, 0, null, null, null);

            assertThat(blank.courseCode()).isNull();
            assertThat(blank.answers()).isEmpty();
            assertThat(blank.hasImage()).isFalse();
        }

        @Test
        @DisplayName("a null ELEMENT survives construction so the validator can name it (E1.11)")
        void draftSurvivesANullElement() {
            // Member A's contract read, finding 1: List.copyOf throws on a null element inside
            // the canonical constructor, which runs on the socket read thread during
            // deserialization - so ["a", null, "c", "d"] used to disconnect the teacher with
            // no dialog. Delete the tolerant copy and THIS test fails; the old
            // draftDoesNotThrowOnNulls only covered the null LIST and passed either way.
            java.util.List<String> withHole = new java.util.ArrayList<>();
            withHole.add("a"); withHole.add(null); withHole.add("c"); withHole.add("d");

            QuestionDraft draft = new QuestionDraft("11", STEM, withHole, 1, "t",
                    Difficulty.EASY, null);

            // The DTO's whole job here is survival; QuestionValidatorTest owns proving the
            // hole then gets a named refusal (its structural rules run before distinctness).
            assertThat(draft.answers()).containsExactly("a", null, "c", "d");
        }

        @Test
        @DisplayName("an edit round-trips its base version, its action and its bytes")
        void editRoundTrips() throws Exception {
            QuestionEdit restored = roundTrip(edit(ImageAction.REPLACE, png));

            assertThat(restored).isEqualTo(edit(ImageAction.REPLACE, png));
            assertThat(restored.hashCode()).isEqualTo(edit(ImageAction.REPLACE, png).hashCode());
            assertThat(restored.displayId5()).isEqualTo("11005");
            assertThat(restored.baseVersionNo()).isEqualTo(3);
            assertThat(restored.imageAction()).isEqualTo(ImageAction.REPLACE);
            assertThat(restored.image()).containsExactly(png);
            assertThat(restored.hasImage()).isTrue();
        }

        @Test
        @DisplayName("a missing image action means KEEP, the instruction that changes nothing")
        void missingImageActionMeansKeep() throws Exception {
            QuestionEdit restored = roundTrip(edit(null, null));

            assertThat(restored.imageAction()).isEqualTo(ImageAction.KEEP);
            assertThat(restored.hasImage()).isFalse();
            assertThat(restored.image()).isNull();
        }

        @Test
        @DisplayName("an edit copies its bytes in and out")
        void editCopiesTheImage() {
            byte[] source = png.clone();
            QuestionEdit edit = edit(ImageAction.REPLACE, source);

            source[1] = 0;
            assertThat(edit.image()[1]).isEqualTo((byte) 'P');

            edit.image()[1] = 0;
            assertThat(edit.image()[1]).isEqualTo((byte) 'P');
        }

        @Test
        @DisplayName("two edits built from the same inputs are equal, bytes compared by value")
        void editComparesByValue() {
            assertThat(edit(ImageAction.REPLACE, png.clone()))
                    .isEqualTo(edit(ImageAction.REPLACE, png.clone()));
            assertThat(edit(ImageAction.REPLACE, png.clone()))
                    .hasSameHashCodeAs(edit(ImageAction.REPLACE, png.clone()));
            QuestionEdit same = edit(ImageAction.KEEP, null);
            assertThat(same).isEqualTo(same);
            assertThat(edit(ImageAction.REPLACE, png)).isNotEqualTo(edit(ImageAction.REMOVE, png));
            assertThat(edit(ImageAction.REPLACE, png))
                    .isNotEqualTo(edit(ImageAction.REPLACE, new byte[]{9}));
            assertThat(edit(ImageAction.KEEP, null)).isNotEqualTo("not an edit");
            assertThat(edit(ImageAction.KEEP, null)).isNotEqualTo(
                    new QuestionEdit("11005", 4, STEM, ANSWERS, 2, "גאומטריה", Difficulty.HARD,
                            ImageAction.KEEP, null));
        }

        @Test
        @DisplayName("an edit never prints the key or the picture into a log line")
        void editToStringIsSafe() {
            String text = edit(ImageAction.REPLACE, png).toString();

            assertThat(text)
                    .contains("displayId5=11005")
                    .contains("baseVersionNo=3")
                    .contains("imageAction=REPLACE")
                    .contains("8 bytes")
                    .doesNotContain(STEM)
                    .doesNotContain("שש");
            assertThat(edit(ImageAction.KEEP, null).toString()).contains("image=none");
        }

        @Test
        @DisplayName("an edit also survives a null ELEMENT (E1.11, same finding as the draft)")
        void editSurvivesANullElement() {
            java.util.List<String> withHole = new java.util.ArrayList<>();
            withHole.add("a"); withHole.add(null); withHole.add("c"); withHole.add("d");

            QuestionEdit edit = new QuestionEdit("11005", 2, STEM, withHole, 1, "t",
                    Difficulty.EASY, ImageAction.KEEP, null);

            assertThat(edit.answers()).containsExactly("a", null, "c", "d");
        }

        @Test
                @DisplayName("an edit's answers are copied, and a null list becomes empty")
        void editCopiesAnswers() {
            List<String> mutable = new ArrayList<>(ANSWERS);
            QuestionEdit fromMutable = new QuestionEdit("11005", 1, STEM, mutable, 1, "t",
                    Difficulty.EASY, ImageAction.KEEP, null);
            mutable.add("חמש");

            assertThat(fromMutable.answers()).hasSize(4);
            assertThat(new QuestionEdit("11005", 1, STEM, null, 1, "t", Difficulty.EASY,
                    ImageAction.KEEP, null).answers()).isEmpty();
        }
    }

    // ===================== deleting =======================================

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("a delete request carries the same concurrency token an edit does")
        void deleteRequestRoundTrips() throws Exception {
            QuestionDeleteRequest restored = roundTrip(new QuestionDeleteRequest("11005", 3));

            assertThat(restored.displayId5()).isEqualTo("11005");
            assertThat(restored.baseVersionNo()).isEqualTo(3);
        }

        @Test
        @DisplayName("a blocking exam round-trips its display id and its Hebrew name")
        void blockingExamRoundTrips() throws Exception {
            BlockingExam restored = roundTrip(new BlockingExam("101101", "מבחן אמצע באלגברה"));

            assertThat(restored).isEqualTo(new BlockingExam("101101", "מבחן אמצע באלגברה"));
            assertThat(restored.displayId6()).isEqualTo("101101");
            assertThat(restored.name()).isEqualTo("מבחן אמצע באלגברה");
        }

        @Test
        @DisplayName("a blocking exam the dialog could not render fails loudly")
        void blockingExamNullChecks() {
            assertThatThrownBy(() -> new BlockingExam(null, "מבחן"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("displayId6");
            assertThatThrownBy(() -> new BlockingExam("101101", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("a successful delete round-trips with nothing blocking it")
        void deletedOutcome() throws Exception {
            DeleteOutcome restored = roundTrip(DeleteOutcome.succeeded());

            assertThat(restored.deleted()).isTrue();
            assertThat(restored.isBlocked()).isFalse();
            assertThat(restored.blockingExams()).isEmpty();
            assertThat(restored.blockingExams().size()).isZero();
        }

        @Test
        @DisplayName("a refusal names the exams, so T-2.7's dialog can point at them")
        void blockedOutcome() throws Exception {
            DeleteOutcome original = DeleteOutcome.blockedBy(List.of(
                    new BlockingExam("101101", "מבחן אמצע באלגברה"),
                    new BlockingExam("101102", "מבחן סוף באלגברה")));

            DeleteOutcome restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.deleted()).isFalse();
            assertThat(restored.isBlocked()).isTrue();
            assertThat(restored.blockingExams().size()).isEqualTo(2);
            assertThat(restored.blockingExams())
                    .extracting(BlockingExam::displayId6)
                    .containsExactly("101101", "101102");
        }

        @Test
        @DisplayName("a refusal with no exams to name is not a blocked refusal")
        void refusalWithoutExamsIsNotBlocked() {
            assertThat(new DeleteOutcome(false, null).isBlocked()).isFalse();
        }

        @Test
        @DisplayName("the blocking list is defensively copied and immutable")
        void outcomeDefensivelyCopies() {
            List<BlockingExam> mutable =
                    new ArrayList<>(List.of(new BlockingExam("101101", "מבחן")));
            DeleteOutcome outcome = DeleteOutcome.blockedBy(mutable);
            mutable.add(new BlockingExam("101102", "מבחן אחר"));

            assertThat(outcome.blockingExams().size()).isEqualTo(1);
            assertThatThrownBy(() -> outcome.blockingExams()
                    .add(new BlockingExam("101103", "עוד מבחן")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ===================== images =========================================

    @Nested
    @DisplayName("illustrations")
    class Images {

        private final byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

        @Test
        @DisplayName("an image request is addressed by version, not by question")
        void imageRequestRoundTrips() throws Exception {
            QuestionImageRequest restored = roundTrip(new QuestionImageRequest("11005", 2));

            assertThat(restored.displayId5()).isEqualTo("11005");
            assertThat(restored.versionNo()).isEqualTo(2);
        }

        @Test
        @DisplayName("an image round-trips its bytes and its sniffed content type")
        void imageRoundTrips() throws Exception {
            QuestionImage original = new QuestionImage("11005", 2, QuestionImage.JPEG, jpeg);

            QuestionImage restored = roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());
            assertThat(restored.bytes()).containsExactly(jpeg);
            assertThat(restored.byteCount()).isEqualTo(4);
            assertThat(restored.isEmpty()).isFalse();
            assertThat(restored.contentType()).isEqualTo("image/jpeg");
            assertThat(QuestionImage.PNG).isEqualTo("image/png");
            assertThat(QuestionImage.MAX_BYTES).isEqualTo(2 * 1024 * 1024);
        }

        @Test
        @DisplayName("null bytes become an empty picture rather than a null on the wire")
        void nullBytesBecomeEmpty() throws Exception {
            QuestionImage restored = roundTrip(new QuestionImage("11005", 1, QuestionImage.PNG, null));

            assertThat(restored.bytes()).isEmpty();
            assertThat(restored.byteCount()).isZero();
            assertThat(restored.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the bytes are copied in and out, so no caller can corrupt them")
        void imageCopiesItsBytes() {
            byte[] source = jpeg.clone();
            QuestionImage image = new QuestionImage("11005", 1, QuestionImage.JPEG, source);

            source[0] = 0;
            assertThat(image.bytes()[0]).isEqualTo((byte) 0xFF);

            image.bytes()[0] = 0;
            assertThat(image.bytes()[0]).isEqualTo((byte) 0xFF);
        }

        @Test
        @DisplayName("two images built from the same inputs are equal, bytes compared by value")
        void imageComparesByValue() {
            QuestionImage one = new QuestionImage("11005", 1, QuestionImage.JPEG, jpeg.clone());
            QuestionImage same = new QuestionImage("11005", 1, QuestionImage.JPEG, jpeg.clone());

            assertThat(one).isEqualTo(same).isEqualTo(one);
            assertThat(one).hasSameHashCodeAs(same);
            assertThat(one).isNotEqualTo(
                    new QuestionImage("11005", 2, QuestionImage.JPEG, jpeg.clone()));
            assertThat(one).isNotEqualTo(
                    new QuestionImage("11005", 1, QuestionImage.JPEG, new byte[]{1}));
            assertThat(one).isNotEqualTo("not an image");
        }

        @Test
        @DisplayName("an image never prints a megabyte of picture into a log line")
        void imageToStringIsSafe() {
            String text = new QuestionImage("11005", 2, QuestionImage.PNG, jpeg).toString();

            assertThat(text)
                    .contains("displayId5=11005")
                    .contains("versionNo=2")
                    .contains("contentType=image/png")
                    .contains("bytes=4");
        }

        @Test
        @DisplayName("an image the server could not have built fails loudly")
        void imageNullChecks() {
            assertThatThrownBy(() -> new QuestionImage(null, 1, QuestionImage.PNG, jpeg))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("displayId5");
            assertThatThrownBy(() -> new QuestionImage("11005", 1, null, jpeg))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("contentType");
        }
    }
}
