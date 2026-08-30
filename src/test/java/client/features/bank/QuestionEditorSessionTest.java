package client.features.bank;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.bank.Difficulty;
import common.dto.bank.ImageAction;
import common.dto.bank.QuestionDetail;
import client.ui.components.logic.ImagePickerLogic;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import server.features.bank.BankMessages;
import server.features.bank.QuestionValidator;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QuestionEditorSession} — E6.10's form and E6.11's live rules, without a toolkit.
 *
 * <p>Three things here are the point and the rest is scaffolding around them: that the live
 * duplicate check reaches <b>the server's</b> verdict rather than a lookalike, that a server
 * refusal lands under the box it is about (T-2.2), and that an editor for an illustrated question
 * cannot be built without its illustration.
 */
class QuestionEditorSessionTest {

    private static final Instant WHEN = Instant.parse("2026-03-10T07:00:00Z");

    /**
     * A genuine 1x1 PNG.
     *
     * <p>Genuine because the picker sniffs the leading bytes and refuses a fake, which this test
     * found out by asserting against a hand-written seven-byte stub and watching
     * {@code chosenBytes()} come back null. Worth keeping the note: a fixture the product would
     * reject demonstrates nothing, and the rejection is the component being right.
     */
    private static final byte[] PICTURE = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwA"
                    + "EhQGAhKmMIQAAAABJRU5ErkJggg==");

    private FakeClientConnection connection;
    private RequestDispatcher dispatcher;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
    }

    private static QuestionDetail detail(boolean hasImage, int versionNo, int latest) {
        return new QuestionDetail("11005", "11", "אלגברה", versionNo, latest, "Read the diagram",
                List.of("Twelve", "Fourteen", "Sixteen", "Eighteen"), 3, "Geometry",
                Difficulty.HARD, hasImage, "דנה כהן", WHEN);
    }

    private QuestionEditorSession editing(boolean hasImage, byte[] bytes) {
        return QuestionEditorSession.forEdit(dispatcher, new DirectFxThreadPoster(),
                detail(hasImage, 2, 2), bytes).onChange(() -> renders++);
    }

    private QuestionEditorSession creating() {
        return QuestionEditorSession.forCreate(dispatcher, new DirectFxThreadPoster(), "11")
                .onChange(() -> renders++);
    }

    private static void fillIn(QuestionEditorSession session) {
        session.setText("Which of these is prime?");
        session.setAnswer(1, "Two");
        session.setAnswer(2, "Four");
        session.setAnswer(3, "Six");
        session.setAnswer(4, "Eight");
        session.setCorrectAnswer(1);
        session.setTopic("Number theory");
        session.setDifficulty(Difficulty.EASY);
    }

    // ===================== The illustration gate ⚑ ========================

    @Nested
    @DisplayName("an editor cannot open without the illustration it is about")
    class ImageGate {

        @Test
        @DisplayName("the picker's logic arrives already loaded, so it never shows the wrong state")
        void loadedAtConstruction() {
            QuestionEditorSession session = editing(true, PICTURE);

            assertThat(session.imageLogic().action())
                    .as("nothing has happened to the picture yet")
                    .isEqualTo(ImageAction.KEEP);
            assertThat(session.imageLogic().remove())
                    .as("and the logic knows there IS one, which is what makes Remove mean "
                            + "something")
                    .matches(outcome -> outcome.isAccepted());
            assertThat(session.imageLogic().action()).isEqualTo(ImageAction.REMOVE);
        }

        @Test
        @DisplayName("⚑ an illustrated question with no bytes is REFUSED, not quietly accepted")
        void theGateIsAGateAndNotAParameter() {
            assertThatThrownBy(() -> editing(true, null))
                    .as("a required argument that accepts null is required in name only. A cold "
                            + "read found this class claiming the parameter alone made the bad "
                            + "state unreachable while this very test file constructed it.")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("11005")
                    .hasMessageContaining("QUESTION_IMAGE_GET");

            assertThatThrownBy(() -> editing(true, new byte[0]))
                    .as("an empty blob is the same absence; BankView already renders it as a "
                            + "failure, so it must not be a way in either")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the harm the gate prevents, so the reason is not only prose ⚑")
        void whatTheBadStateWouldHaveCost() {
            // The state the gate now refuses, built directly from the component to show what it
            // would have done. This is about ImagePickerLogic, not about the gate, and it is
            // here so the gate's cost is written down where somebody weakening it would read it.
            ImagePickerLogic blind = ImagePickerLogic.of(null);

            blind.remove();

            assertThat(blind.action())
                    .as("she pressed Remove on a question that HAS a picture, the picker believed "
                            + "it had none, and the server is told KEEP. Her removal is discarded "
                            + "with no error anywhere.")
                    .isEqualTo(ImageAction.KEEP);
        }

        @Test
        @DisplayName("a question with no illustration loads an empty picker, not a broken one")
        void noImageIsFine() {
            QuestionEditorSession session = editing(false, null);

            assertThat(session.imageLogic().action()).isEqualTo(ImageAction.KEEP);
            assertThat(session.imageLogic().chosenBytes()).isNull();
        }

        @Test
        @DisplayName("a new question starts with an empty picker")
        void createStartsEmpty() {
            assertThat(creating().imageLogic().action()).isEqualTo(ImageAction.KEEP);
        }
    }

    // ===================== E6.11, the live rules ⚑ ========================

    @Nested
    @DisplayName("the duplicate rule, reaching the server's verdict")
    class Duplicates {

        /**
         * The contract's own table of pairs MySQL calls one answer, section 5. If the editor
         * accepted any of these, the save would be refused by a sentence the live check promised
         * would not come.
         *
         * <p><b>Two of these rows are spacing rather than collation</b> — {@code '1 2 3'}/{@code 123}
         * and {@code '  Two  '}/{@code Two}. MySQL calls those two <em>different</em>; the service
         * folds them anyway because ADR-016 names trimming and whitespace collapse as the rule, and
         * contract §5 amendment A2 keeps that half deliberately. They belong in this list because
         * the editor must reach the same verdict as the server, which is what this class is about
         * — but the docstring's "pairs MySQL calls one answer" is not true of them, and saying so
         * here is cheaper than the next reader re-deriving it.
         */
        @ParameterizedTest
        @CsvSource({
                "resume,résumé",
                "Strasse,Straße",
                "oeuvre,œuvre",
                "A,Ａ",
                "τέλος,τέλοσ",
                "שלום,שָׁלוֹם",
                "'1 2 3',123",
                "Two,two",
                "'  Two  ',Two"
        })
        @DisplayName("every pair the storage constraint would reject is refused here too")
        void refusesWhatTheServerWould(String first, String second) {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setAnswer(1, first);
            session.setAnswer(2, second);

            assertThat(QuestionValidator.sameAnswer(first, second))
                    .as("sanity: the fixture is a pair the shared rule folds together")
                    .isTrue();
            assertThat(session.liveProblems())
                    .extracting(QuestionEditorCopy.Refusal::message)
                    .contains(BankMessages.answersDuplicated(1, 2));
            assertThat(session.canSave()).isFalse();
        }

        /**
         * ⚑ {@code co-op}/{@code coop} moved here from the refusal list on 2026-08-26 (B-7,
         * BANK contract amendment A1). It used to fold, because Java's {@code Collator} at PRIMARY
         * strength drops the hyphen entirely — and {@code utf8mb4_unicode_ci} does not, measured.
         * The old row therefore pinned the defect: it asserted the editor refuses a pair the
         * database would have stored, under a docstring claiming these are pairs "MySQL calls one
         * answer". It was the walk of case 2.4, not this suite, that priced what that cost — five
         * seeded questions no teacher could re-save.
         */
        @ParameterizedTest
        @CsvSource({"cat.,cat", "it's,its", "3+4,34", "A(1),A1", "Two,Three", "co-op,coop"})
        @DisplayName("and pairs it would accept are accepted, so the rule is not merely strict")
        void acceptsWhatTheServerWould(String first, String second) {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setAnswer(1, first);
            session.setAnswer(2, second);

            assertThat(QuestionValidator.sameAnswer(first, second)).isFalse();
            assertThat(session.liveProblems()).isEmpty();
            assertThat(session.canSave()).isTrue();
        }

        @Test
        @DisplayName("both boxes of a colliding pair are marked, not just the later one")
        void bothPositionsAreNamed() {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setAnswer(2, "Two");

            assertThat(session.liveProblems())
                    .extracting(QuestionEditorCopy.Refusal::position)
                    .as("a teacher told answer 2 is a duplicate has to hunt for which other one")
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("half-typed blanks do not flood the form with duplicate warnings")
        void blanksAreNotDuplicates() {
            QuestionEditorSession session = creating();
            session.setText("Which of these is prime?");
            session.setAnswer(1, "Two");

            assertThat(session.liveProblems())
                    .as("three empty boxes are identical under any comparison, and saying so "
                            + "while she is still filling them in is noise")
                    .isEmpty();
            assertThat(session.canSave())
                    .as("but the form is still not savable")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the length rules, in the columns' own numbers")
    class Lengths {

        @Test
        @DisplayName("an over-long stem is refused with the server's sentence")
        void stem() {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setText("x".repeat(QuestionValidator.MAX_TEXT_LENGTH + 1));

            assertThat(session.liveProblems())
                    .extracting(QuestionEditorCopy.Refusal::field,
                            QuestionEditorCopy.Refusal::message)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(
                            QuestionEditorCopy.Field.TEXT,
                            BankMessages.textTooLong(QuestionValidator.MAX_TEXT_LENGTH)));
        }

        @Test
        @DisplayName("exactly at the limit is allowed, one over is not")
        void boundary() {
            QuestionEditorSession session = creating();
            fillIn(session);

            session.setTopic("t".repeat(QuestionValidator.MAX_TOPIC_LENGTH));
            assertThat(session.liveProblems()).isEmpty();

            session.setTopic("t".repeat(QuestionValidator.MAX_TOPIC_LENGTH + 1));
            assertThat(session.liveProblems())
                    .extracting(QuestionEditorCopy.Refusal::field)
                    .containsExactly(QuestionEditorCopy.Field.TOPIC);
        }

        @Test
        @DisplayName("an over-long answer names its position")
        void answerNamesItsBox() {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setAnswer(3, "a".repeat(QuestionValidator.MAX_ANSWER_LENGTH + 1));

            assertThat(session.liveProblems())
                    .extracting(QuestionEditorCopy.Refusal::position)
                    .containsExactly(3);
        }
    }

    @Nested
    @DisplayName("what blocks the Save button")
    class Saving {

        @Test
        @DisplayName("a complete, consistent form is savable and an incomplete one is not")
        void completeness() {
            QuestionEditorSession session = creating();
            assertThat(session.canSave()).isFalse();

            fillIn(session);
            assertThat(session.canSave()).isTrue();

            session.setCorrectAnswer(null);
            assertThat(session.canSave())
                    .as("C-8: no key, no question")
                    .isFalse();
            session.setCorrectAnswer(2);

            session.setDifficulty(null);
            assertThat(session.canSave()).isFalse();
            session.setDifficulty(Difficulty.MEDIUM);

            session.setTopic("   ");
            assertThat(session.canSave()).isFalse();
        }

        @Test
        @DisplayName("a save in flight blocks a second one")
        void noDoubleSave() {
            QuestionEditorSession session = creating();
            fillIn(session);

            session.save();
            session.save();

            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(session.isSaving()).isTrue();
            assertThat(session.canSave()).isFalse();
        }
    }

    @Nested
    @DisplayName("what goes on the wire")
    class Wire {

        @Test
        @DisplayName("a new question is a QuestionDraft with no id and no author")
        void create() {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.imageLogic().choose(PICTURE, "diagram.png");

            session.save();

            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.QUESTION_CREATE);
            QuestionDraft sent = (QuestionDraft) connection.lastSent().getPayload();
            assertThat(sent.courseCode()).isEqualTo("11");
            assertThat(sent.answers()).containsExactly("Two", "Four", "Six", "Eight");
            assertThat(sent.correctAnswer()).isEqualTo(1);
            assertThat(sent.image())
                    .as("the bytes come from the picker's own logic, never assembled by hand")
                    .isEqualTo(PICTURE);
        }

        @Test
        @DisplayName("an edit carries the version she was shown, which is the staleness token")
        void edit() {
            QuestionEditorSession session = editing(true, PICTURE);
            session.setText("Read the diagram carefully");

            session.save();

            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.QUESTION_UPDATE);
            QuestionEdit sent = (QuestionEdit) connection.lastSent().getPayload();
            assertThat(sent.displayId5()).isEqualTo("11005");
            assertThat(sent.baseVersionNo())
                    .as("two teachers who both opened v2 must collide, not overwrite")
                    .isEqualTo(2);
            assertThat(sent.imageAction()).isEqualTo(ImageAction.KEEP);
            assertThat(sent.image()).isNull();
        }

        @Test
        @DisplayName("the image pair travels together, so a cancel can never become a REMOVE")
        void imagePairIsConsistent() {
            QuestionEditorSession session = editing(true, PICTURE);
            // Opening the chooser and cancelling: the component defines null as "nothing
            // happened", which is the defect the server refuses and the client must not produce.
            session.imageLogic().choose(null, "cancelled.png");
            // U-57: a cancelled chooser is not a change, so on its own this form is unchanged
            // and Save is off. One real edit lets the save go, and the pair is still KEEP/null.
            session.setText("Read the diagram and answer, then explain");

            session.save();

            QuestionEdit sent = (QuestionEdit) connection.lastSent().getPayload();
            assertThat(sent.imageAction()).isEqualTo(ImageAction.KEEP);
            assertThat(sent.image()).isNull();
        }
    }

    // ===================== T-2.2: three bad saves, three sentences ⚑ ======

    @Nested
    @DisplayName("a server refusal lands under the box it is about")
    class Mapping {

        private QuestionEditorSession refusedWith(String message) {
            QuestionEditorSession session = creating();
            fillIn(session);
            connection.replyError(Verb.QUESTION_CREATE, ErrorCode.VALIDATION, message);
            session.save();
            return session;
        }

        @Test
        @DisplayName("the stem's refusal goes on the stem")
        void text() {
            QuestionEditorSession session = refusedWith(BankMessages.TEXT_REQUIRED);

            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.REFUSED);
            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field)
                    .isEqualTo(QuestionEditorCopy.Field.TEXT);
        }

        @Test
        @DisplayName("the topic's refusal goes on the topic, not on the stem")
        void topic() {
            QuestionEditorSession session = refusedWith(BankMessages.TOPIC_REQUIRED);

            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field)
                    .as("exact equality against BankMessages, so one field's sentence cannot "
                            + "land under another's box")
                    .isEqualTo(QuestionEditorCopy.Field.TOPIC);
        }

        @Test
        @DisplayName("a blank answer's refusal names its own box")
        void answerPosition() {
            QuestionEditorSession session = refusedWith(BankMessages.answerBlank(3));

            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field,
                            QuestionEditorCopy.Refusal::position)
                    .containsExactly(QuestionEditorCopy.Field.ANSWER, 3);
        }

        @Test
        @DisplayName("the duplicate refusal names the position the server named")
        void duplicatePair() {
            QuestionEditorSession session = refusedWith(BankMessages.answersDuplicated(2, 4));

            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::position)
                    .as("QuestionValidator.answersDistinct reports answerField(j), the later box")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("the key's refusal goes on the radio group")
        void correctAnswer() {
            QuestionEditorSession session = refusedWith(BankMessages.CORRECT_ANSWER_RANGE);

            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field)
                    .isEqualTo(QuestionEditorCopy.Field.CORRECT_ANSWER);
        }

        @Test
        @DisplayName("an image refusal goes on the picker")
        void image() {
            QuestionEditorSession session = refusedWith(BankMessages.IMAGE_TOO_LARGE);

            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field)
                    .isEqualTo(QuestionEditorCopy.Field.IMAGE);
        }

        @Test
        @DisplayName("a sentence the catalogue does not hold becomes a dialog, not a silence")
        void unknownSentence() {
            QuestionEditorSession session = refusedWith("Something nobody wrote down");

            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field,
                            QuestionEditorCopy.Refusal::message)
                    .as("swallowing it would leave her with a form that refuses to save and no "
                            + "reason anywhere on screen")
                    .containsExactly(QuestionEditorCopy.Field.FORM, "Something nobody wrote down");
        }

        @Test
        @DisplayName("editing after a refusal clears it, so a fixed field stops being red")
        void refusalClearsOnEdit() {
            QuestionEditorSession session = refusedWith(BankMessages.TEXT_REQUIRED);
            assertThat(session.refusals()).isNotEmpty();

            session.setText("Now it says something");

            assertThat(session.refusals()).isEmpty();
            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.NONE);
        }
    }

    // ===================== The other endings ==============================

    @Nested
    @DisplayName("the endings that are not a refusal")
    class Endings {

        @Test
        @DisplayName("a written question is adopted, so the editor is no longer dirty")
        void saved() {
            QuestionEditorSession session = editing(false, null);
            session.setText("Read the diagram carefully");
            assertThat(session.isDirty()).isTrue();
            connection.replyOk(Verb.QUESTION_UPDATE, detail(false, 3, 3));

            session.save();

            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.SAVED);
            assertThat(session.saved()).isPresent();
            assertThat(session.isDirty())
                    .as("what is on screen is now what the server holds")
                    .isFalse();
        }

        @Test
        @DisplayName("a stale edit is CONFLICT and is never offered as an overwrite")
        void stale() {
            QuestionEditorSession session = editing(false, null);
            session.setText("Mine");
            connection.replyError(Verb.QUESTION_UPDATE, ErrorCode.CONFLICT,
                    BankMessages.STALE_EDIT);

            session.save();

            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.STALE);
            assertThat(session.refusals())
                    .as("there is no field to blame and nothing for her to fix here")
                    .isEmpty();
        }

        @Test
        @DisplayName("a question deleted mid-edit is GONE, not a validation problem")
        void gone() {
            QuestionEditorSession session = editing(false, null);
            session.setText("Mine");
            connection.replyError(Verb.QUESTION_UPDATE, ErrorCode.NOT_FOUND,
                    BankMessages.QUESTION_NOT_FOUND);

            session.save();

            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.GONE);
        }

        @Test
        @DisplayName("a save that never reached a decision says nothing was changed")
        void failed() {
            QuestionEditorSession session = creating();
            fillIn(session);
            connection.replyError(Verb.QUESTION_CREATE, ErrorCode.INTERNAL, "boom");

            session.save();

            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.FAILED);
        }

        @Test
        @DisplayName("an OK carrying the wrong type is a failure, not a crash")
        void wrongPayload() {
            QuestionEditorSession session = creating();
            fillIn(session);
            connection.replyOk(Verb.QUESTION_CREATE, "not a question");

            session.save();

            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.FAILED);
        }
    }

    // ===================== Dirty tracking =================================

    @Nested
    @DisplayName("unsaved changes")
    class Dirty {

        @Test
        @DisplayName("an editor just opened on a question is not dirty")
        void openIsClean() {
            assertThat(editing(true, PICTURE).isDirty())
                    .as("filling the form in from a QuestionDetail must not mark it dirty, or the "
                            + "discard prompt fires on every Cancel")
                    .isFalse();
        }

        @Test
        @DisplayName("every field marks it dirty, including the picture")
        void everyFieldCounts() {
            QuestionEditorSession session = editing(true, PICTURE);
            session.setTopic("Shapes");
            assertThat(session.isDirty()).isTrue();

            QuestionEditorSession other = editing(true, PICTURE);
            other.imageLogic().remove();
            assertThat(other.isDirty())
                    .as("a pending removal is a change she would lose")
                    .isTrue();
        }

        @Test
        @DisplayName("typing a value back to what it was is not a change")
        void revertingIsClean() {
            QuestionEditorSession session = editing(false, null);
            session.setTopic("Shapes");
            session.setTopic("Geometry");

            assertThat(session.isDirty()).isFalse();
        }
    }

    // ===================== What the cold audit found ======================

    @Nested
    @DisplayName("findings from the cold audit of this PR")
    class AuditFindings {

        @Test
        @DisplayName("the staleness token moves to the version the server just wrote ⚑")
        void baseVersionFollowsTheSave() {
            QuestionEditorSession session = editing(false, null);
            session.setText("Read the diagram carefully");
            connection.replyOk(Verb.QUESTION_UPDATE, detail(false, 3, 3));
            session.save();

            session.setText("And again");
            connection.clearSent();
            connection.replyOk(Verb.QUESTION_UPDATE, detail(false, 4, 4));
            session.save();

            QuestionEdit second = (QuestionEdit) connection.lastSent().getPayload();
            assertThat(second.baseVersionNo())
                    .as("a second save that still cited v2 would be refused as CONFLICT and the "
                            + "screen would tell her somebody else edited the question, about "
                            + "her own previous save")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a question somebody else is editing cannot be saved from here ⚑")
        void readOnlyBlocksTheSave() {
            QuestionEditorSession session = editing(false, null);
            session.setText("Mine now");
            assertThat(session.canSave())
                    .as("a complete form with the lock free is savable")
                    .isTrue();

            session.setReadOnly(true);

            assertThat(session.canSave())
                    .as("E18 gives the lock to the other teacher, and the contract answers "
                            + "CONFLICT for a question locked by somebody else. Offering Save "
                            + "here offers an attempt with one outcome.")
                    .isFalse();
            assertThat(session.isReadOnly()).isTrue();

            session.setReadOnly(false);
            assertThat(session.canSave())
                    .as("and it comes back when the other editor releases, without a reload")
                    .isTrue();
        }

        @Test
        @DisplayName("read-only stops the save even when nothing else is wrong")
        void readOnlyIsNotAValidationProblem() {
            QuestionEditorSession session = editing(false, null);
            session.setText("Mine now");
            session.setReadOnly(true);

            assertThat(session.liveProblems())
                    .as("she has typed nothing wrong; the reason lives on the banner, not under "
                            + "a field, so a red box would be blaming her form for someone "
                            + "else's lock")
                    .isEmpty();
        }

        @Test
        @DisplayName("setting read-only to what it already is costs no render")
        void readOnlyIsIdempotent() {
            QuestionEditorSession session = editing(false, null);
            session.setReadOnly(true);
            int before = renders;

            session.setReadOnly(true);

            assertThat(renders)
                    .as("lock pushes arrive on a heartbeat; re-rendering the form on every one "
                            + "would rebuild the answer rows under her caret")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the live rule still marks both boxes, which is where 'both' was true")
        void liveRuleStillMarksBoth() {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setAnswer(4, "Two");

            assertThat(session.liveProblems())
                    .extracting(QuestionEditorCopy.Refusal::position)
                    .containsExactly(1, 4);
        }
    }

    // ===================== The small surfaces =============================

    @Nested
    @DisplayName("guards and readers the view leans on")
    class Surfaces {

        @Test
        @DisplayName("setting a field to what it already holds costs no render")
        void settersAreIdempotent() {
            QuestionEditorSession session = editing(false, null);
            int before = renders;

            session.setText("Read the diagram");
            session.setAnswer(1, "Twelve");
            session.setCorrectAnswer(3);
            session.setTopic("Geometry");
            session.setDifficulty(Difficulty.HARD);

            assertThat(renders)
                    .as("a form that re-renders on every keystroke that changed nothing would "
                            + "rebuild the answer rows under the caret")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("an answer position outside 1..4 is ignored rather than thrown at")
        void answerBoundsAreGuarded() {
            QuestionEditorSession session = editing(false, null);

            session.setAnswer(0, "nope");
            session.setAnswer(5, "nope");

            assertThat(session.answer(0)).isEmpty();
            assertThat(session.answer(5)).isEmpty();
            assertThat(session.answers()).containsExactly("Twelve", "Fourteen", "Sixteen",
                    "Eighteen");
        }

        @Test
        @DisplayName("a picture change marks the form dirty like any other edit")
        void imageChangedIsAnEdit() {
            QuestionEditorSession session = editing(true, PICTURE);
            session.imageLogic().remove();

            session.imageChanged();

            assertThat(session.isDirty()).isTrue();
        }

        @Test
        @DisplayName("the readers say what the editor was opened on")
        void readers() {
            QuestionEditorSession edit = editing(false, null);
            assertThat(edit.mode()).isEqualTo(QuestionEditorSession.Mode.EDIT);
            assertThat(edit.courseCode()).isEqualTo("11");
            assertThat(edit.displayId5()).isEqualTo("11005");
            assertThat(edit.baseVersionNo()).isEqualTo(2);
            assertThat(edit.text()).isEqualTo("Read the diagram");
            assertThat(edit.correctAnswer()).isEqualTo(3);
            assertThat(edit.topic()).isEqualTo("Geometry");
            assertThat(edit.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(edit.isSaving()).isFalse();

            QuestionEditorSession create = creating();
            assertThat(create.mode()).isEqualTo(QuestionEditorSession.Mode.CREATE);
            assertThat(create.displayId5()).isNull();
            assertThat(create.baseVersionNo()).isZero();
        }

        @Test
        @DisplayName("a blank answer alone blocks the save")
        void oneBlankAnswerBlocks() {
            QuestionEditorSession session = creating();
            fillIn(session);
            session.setAnswer(4, "   ");

            assertThat(session.canSave()).isFalse();
        }

        @Test
        @DisplayName("the difficulty refusal goes on the picker, and a blank one becomes a dialog")
        void remainingMappings() {
            QuestionEditorSession session = creating();
            fillIn(session);
            connection.replyError(Verb.QUESTION_CREATE, ErrorCode.VALIDATION,
                    BankMessages.DIFFICULTY_REQUIRED);
            session.save();
            assertThat(session.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field)
                    .isEqualTo(QuestionEditorCopy.Field.DIFFICULTY);

            QuestionEditorSession other = creating();
            fillIn(other);
            connection.replyError(Verb.QUESTION_CREATE, ErrorCode.VALIDATION, "  ");
            other.save();
            assertThat(other.refusals()).singleElement()
                    .extracting(QuestionEditorCopy.Refusal::field,
                            QuestionEditorCopy.Refusal::message)
                    .as("a refusal with no sentence still has to say something")
                    .containsExactly(QuestionEditorCopy.Field.FORM,
                            QuestionEditorCopy.SAVE_FAILED);
        }

        @Test
        @DisplayName("the outcome is dismissible, and dismissing twice is a no-op")
        void dismissing() {
            QuestionEditorSession session = creating();
            fillIn(session);
            connection.replyError(Verb.QUESTION_CREATE, ErrorCode.VALIDATION,
                    BankMessages.TEXT_REQUIRED);
            session.save();

            session.dismissOutcome();
            assertThat(session.outcome()).isEqualTo(QuestionEditorSession.Outcome.NONE);
            assertThat(session.refusals()).isEmpty();

            int before = renders;
            session.dismissOutcome();
            assertThat(renders).isEqualTo(before);
        }

        @Test
        @DisplayName("a detail carrying fewer than four answers fills the rest with blanks")
        void shortAnswerList() {
            QuestionDetail truncated = new QuestionDetail("11005", "11", "אלגברה", 1, 1, "text",
                    List.of("One", "Two"), 1, "Geometry", Difficulty.EASY, false, "דנה", WHEN);

            QuestionEditorSession session = QuestionEditorSession.forEdit(dispatcher,
                    new DirectFxThreadPoster(), truncated, null);

            assertThat(session.answers())
                    .as("the server cannot send this, but a form with two boxes and two nulls "
                            + "would be a NullPointerException on the first keystroke")
                    .containsExactly("One", "Two", "", "");
        }
    }
}
