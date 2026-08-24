package server.features.bank;

import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.DeleteOutcome;
import common.dto.bank.Difficulty;
import common.dto.bank.ImageAction;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import server.core.CallerContext;
import server.db.RepositoryTestBase;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.ids.QuestionIdAllocator;
import server.db.repos.CourseRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;
import server.features.locks.EditLockGuard;
import server.features.locks.EditLockService;
import server.features.locks.DisplayNames;
import server.realtime.PushGateway;
import server.core.SessionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question bank, assembled, against a real MySQL (E6.16).
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p><b>Before it, no test in {@code server.features.bank} touched a database.</b> Every one of the
 * five server-side bank test classes declares {@code @Mock private Session session}; the
 * repositories are proven on two engines by {@code BankBrowseContract} and
 * {@code BankRepositoryContract}, and the services and handlers are proven against mocks. The seam
 * between them was proven nowhere. Measured rather than assumed: grepping
 * {@code src/test/java/server/features} for {@code RepositoryTestBase} returns approval, exam,
 * grading, notify, release, reports and results. It did not return bank.
 *
 * <p>So everything here is a property of the <em>composition</em> - true of the stack assembled,
 * not of any class in it. Anything provable against one layer belongs in that layer's test and is
 * deliberately not repeated here.
 *
 * <h2>MySQL only, and that is not laziness</h2>
 *
 * <p>There is no H2 leaf because an H2 leaf would pass for reasons unrelated to what this class
 * claims. {@code TestDatabases}' own javadoc says the H2 schema is generated from the entities and
 * "reproduces no foreign keys and no CHECK constraints, and not the {@code utf8mb4_unicode_ci}
 * collation". Every headline assertion below needs exactly one of those three:
 * {@code ck_question_versions_distinct} for the validator agreement, the collation for Hebrew, and
 * a real row lock for the allocator.
 *
 * <h2>The rule every assertion here follows</h2>
 *
 * <p><b>Nothing is asserted against the value a write returned.</b> {@code QuestionService.create}
 * answers a {@code QuestionDetail} assembled from the objects it just built in memory, so asserting
 * on it proves nothing about the INSERT. Every check is a <em>second call, in a new transaction</em>,
 * through the read handlers. The handlers open one {@code Transactions.inTx} per call, which is
 * what makes the re-read see committed state rather than Hibernate's first-level cache.
 *
 * <p>For the same reason the question always arrives through {@code QUESTION_CREATE} rather than
 * being persisted directly. Seeding entities here would bypass {@link QuestionValidator},
 * {@code QuestionIdAllocator}, {@code BankDetails} and {@code QuestionImages} at once, and this
 * class would become {@code BankBrowseContract} with longer imports.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class BankRoundTripIntegrationTest extends RepositoryTestBase {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    /** Hebrew throughout: this is the language the system is written in and demoed in. */
    private static final String TEXT = "מהו השורש הריבועי של שמונים ואחת?";
    private static final String TOPIC = "שורשים";
    private static final List<String> ANSWERS = List.of("תשע", "שמונה", "שבע", "שש");

    private BankHandlers writes;
    private BankReadHandlers reads;

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @BeforeEach
    void buildTheBank() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DisplayNames names = userId -> Optional.of("someone");
        EditLockService locks =
                new EditLockService(new PushGateway(new SessionManager()), names, clock);

        // The real objects, in the arrangement HSTSServer.defaultRouter builds. Assembled by hand
        // rather than through the router so the test can call one verb at a time and read the
        // answer, which is the style ReleaseCloseIntegrationTest and ExamConcurrencyIntegrationTest
        // both use.
        writes = new BankHandlers(factory(),
                new QuestionService(new QuestionRepository(), new CourseRepository(),
                        new UserRepository(), new QuestionIdAllocator(), clock,
                        new EditLockGuard(locks)));
        reads = new BankReadHandlers(factory(),
                new BankBrowseService(new QuestionRepository(), new CourseRepository(),
                        new UserRepository()));
    }

    // ===================== Fixtures =======================================

    private CallerContext dana() {
        return CallerContext.authenticated(Mockito.mock(ConnectionToClient.class), danaId,
                Role.TEACHER);
    }

    private static QuestionDraft draft(List<String> answers, int correctAnswer) {
        return new QuestionDraft(COURSE_ALGEBRA, TEXT, answers, correctAnswer, TOPIC,
                Difficulty.MEDIUM, null);
    }

    /** Sends a verb through the real handler and hands back the answer. */
    private Message create(QuestionDraft draft) {
        return writes.create(dana(), Message.request(Verb.QUESTION_CREATE, draft));
    }

    private Message update(QuestionEdit edit) {
        return writes.update(dana(), Message.request(Verb.QUESTION_UPDATE, edit));
    }

    private static String errorText(Message answer) {
        return ((common.dto.ErrorPayload) answer.getPayload()).message();
    }

    // ===================== The validator against the real constraint ======

    @Nested
    @DisplayName("⚑ the validator's promise, measured against the constraint it names")
    class ValidatorAgreesWithTheConstraint {

        /**
         * {@code QuestionValidator.sameAnswer}'s javadoc makes a one-directional promise: "never
         * accept a pair the database will reject." The database in question is
         * {@code ck_question_versions_distinct} (V2__bank.sql), a plain {@code a1 <> a2} over
         * columns in a {@code utf8mb4_unicode_ci} table.
         *
         * <p><b>Nothing in this repository has ever executed that comparison.</b>
         * {@code QuestionValidatorTest} is pure Java, and until this class no bank test reached
         * MySQL - so the javadoc's "verified against the running database rather than assumed" was
         * a claim about a session somebody had, not about the build.
         *
         * <p>Hebrew final forms are where the two disagreed. Java's {@code Collator} at PRIMARY
         * strength calls ם and מ different letters; {@code utf8mb4_unicode_ci} gives them the same
         * primary weight and calls the strings equal. The validator was therefore LOOSER than the
         * constraint on the one language this system is written in, and a teacher who typed two
         * answers differing only in a final form got past validation and into
         * {@code SQLIntegrityConstraintViolationException} - surfaced as INTERNAL, which is
         * precisely what the validator exists to prevent.
         *
         * <p>This test is the verification the javadoc claimed. It fails on the tree that
         * introduced it and passes on the fix.
         */
        @ParameterizedTest(name = "{2}: {0} vs {1}")
        @CsvSource({
                "מים,   מימ,  final mem",
                "כן,    כנ,   final nun",
                "רף,    רפ,   final pe",
                "ארץ,   ארצ,  final tsadi",
                "דרך,   דרכ,  final kaf",
                "שלום,  שָׁלוֹם, niqqud (these already agreed)"
        })
        @DisplayName("a pair the validator accepts is a pair the database accepts")
        void whatTheValidatorAcceptsTheDatabaseAccepts(String first, String second, String why) {
            // The two halves of the promise, checked in the only order that means anything: ask
            // the validator, then ask the database the same question by actually inserting.
            boolean validatorSaysSame = QuestionValidator.sameAnswer(first, second);

            Message answer = create(draft(List.of(first, second, "אחר", "שונה"), 1));

            if (validatorSaysSame) {
                // Refused before the write. The database never sees the pair, so the promise holds
                // trivially and the teacher gets a sentence naming the field.
                assertThat(answer.isOk())
                        .as("%s: the validator called these the same answer, so the create must be "
                                + "refused with a sentence rather than attempted", why)
                        .isFalse();
                assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            } else {
                // The dangerous branch. The validator let the pair through, so the INSERT happens
                // and the CHECK constraint decides. Anything other than OK here means the
                // validator is looser than the constraint and the teacher meets a stack trace.
                assertThat(answer.isOk())
                        .as("%s: the validator accepted this pair, so the database must accept it "
                                + "too. A refusal here is the P-6 divergence: sameAnswer promises "
                                + "'never accept a pair the database will reject' and this pair "
                                + "proves otherwise. Answer was: %s", why,
                                answer.isOk() ? "OK" : answer.getErrorCode() + " / "
                                        + errorText(answer))
                        .isTrue();
            }
        }
    }

    // ===================== The answer key, across the whole stack =========

    @Nested
    @DisplayName("the answer key still points at the text she marked")
    class TheAnswerKeySurvives {

        /**
         * The ordinal changes representation four times between her form and her screen:
         * {@code int} on {@code QuestionDraft}, narrowed to {@code (byte)} in
         * {@code QuestionService.versionOf}, stored as {@code TINYINT}, read back as {@code byte},
         * widened to {@code int} on {@code QuestionDetail}. Independently,
         * {@code BankDetails.answersOf} rebuilds the option list positionally from a1..a4, with a
         * comment warning that reordering there "would silently repoint the answer key".
         *
         * <p>Every existing assertion about it is of the form {@code isEqualTo(3)}, which restates
         * the input and survives any repointing. This one asserts the ordinal against the TEXT it
         * points at, after a real write and a real re-read.
         */
        @Test
        @DisplayName("⚑ after a create and a re-read, the key points at the same words")
        void theKeyPointsAtTheSameWordsAfterARoundTrip() {
            String marked = ANSWERS.get(2);

            Message created = create(draft(ANSWERS, 3));
            assertThat(created.isOk()).as("create should have been accepted").isTrue();
            String displayId = ((QuestionDetail) created.getPayload()).displayId5();

            QuestionDetail reread = get(displayId);

            assertThat(reread.correctAnswerText())
                    .as("the ordinal survives int -> byte -> TINYINT -> byte -> int and the "
                            + "positional rebuild in BankDetails; asserting the NUMBER would pass "
                            + "even if the options came back in another order")
                    .isEqualTo(marked);
            assertThat(reread.answers())
                    .as("and the options themselves are unreordered, in Hebrew")
                    .containsExactlyElementsOf(ANSWERS);
        }
    }

    // ===================== Versions, and who agrees about which is latest =

    @Nested
    @DisplayName("editing, and the three independent answers to \"which version is newest\"")
    class Versions {

        /**
         * {@code latestVersionNo} is computed three different ways in three different places:
         * arithmetic on the write path in {@code QuestionService}, a {@code setMaxResults(1)}
         * query for the detail read, and a full-history {@code ORDER BY} for the version panel.
         * No test compares any two of them, so any pair could drift and each layer's own suite
         * would stay green.
         */
        @Test
        @DisplayName("⚑ the write path, the detail read and the history all say the same number")
        void allThreeExpressionsOfLatestAgree() {
            String id = createdQuestion();

            Message edited = update(new QuestionEdit(id, 1, TEXT + " (מתוקן)", ANSWERS, 2, TOPIC,
                    Difficulty.HARD, ImageAction.KEEP, null));
            assertThat(edited.isOk()).as("the edit should have been accepted").isTrue();
            int fromTheWrite = ((QuestionDetail) edited.getPayload()).latestVersionNo();

            int fromTheDetailRead = get(id).latestVersionNo();
            List<QuestionVersionDetail> history = versions(id);

            assertThat(fromTheWrite).as("the write path's arithmetic").isEqualTo(2);
            assertThat(fromTheDetailRead)
                    .as("the detail read's setMaxResults(1) query must agree with the arithmetic")
                    .isEqualTo(fromTheWrite);
            assertThat(history.get(0).versionNo())
                    .as("and the history's ORDER BY must put the same version first")
                    .isEqualTo(fromTheWrite);
        }

        @Test
        @DisplayName("version 1 is untouched by the edit, in the database rather than in memory")
        void theOldVersionIsStillThereUnchanged() {
            String id = createdQuestion();

            update(new QuestionEdit(id, 1, "טקסט חדש לגמרי", ANSWERS, 2, TOPIC, Difficulty.HARD,
                    ImageAction.KEEP, null));

            List<QuestionVersionDetail> history = versions(id);
            assertThat(history).extracting(QuestionVersionDetail::versionNo)
                    .as("newest first, both versions present (ADR-011: editing never overwrites)")
                    .containsExactly(2, 1);
            QuestionVersionDetail original = history.get(1);
            assertThat(original.text()).isEqualTo(TEXT);
            assertThat(original.difficulty()).isEqualTo(Difficulty.MEDIUM);
            assertThat(original.correctAnswer())
                    .as("v1's key is untouched by an edit that repointed v2's")
                    .isEqualTo(3);
        }
    }

    // ===================== Delete, observed from outside its transaction ==

    @Nested
    @DisplayName("soft delete, and what it looks like from a later transaction")
    class SoftDelete {

        /**
         * {@code QuestionService.delete} mutates a managed entity and leans on Hibernate's dirty
         * checking at commit. Against a mocked {@code Session} - which is what every other bank
         * test has - the entity is a plain POJO, so the existing assertion proves only that a
         * setter ran. Whether the UPDATE reached the database has never been observed.
         */
        @Test
        @DisplayName("⚑ the delete is committed, not merely set on an object")
        void theDeleteReachesTheDatabase() {
            String id = createdQuestion();

            Message deleted = writes.delete(dana(),
                    Message.request(Verb.QUESTION_DELETE, new QuestionDeleteRequest(id, 1)));
            assertThat(deleted.isOk()).isTrue();
            assertThat(((DeleteOutcome) deleted.getPayload()).deleted())
                    .as("nothing references it, so it should soft-delete rather than block")
                    .isTrue();

            // Both reads run in their own transactions, so neither can be served by the
            // first-level cache of the one that did the writing.
            assertThat(rowsIn(browse(null, null)))
                    .as("gone from the bank list")
                    .doesNotContain(id);
            Message afterwards = reads.get(dana(),
                    Message.request(Verb.QUESTION_GET, new QuestionRequest(id)));
            assertThat(afterwards.isOk())
                    .as("and gone from QUESTION_GET - a soft-deleted question is NOT_FOUND, the "
                            + "same answer as one that never existed (P-5)")
                    .isFalse();
            assertThat(afterwards.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("⚑ the deleted question's serial is not handed out again")
        void theSerialIsRetainedAfterADelete() {
            // AllocatorContract proves MAX+1 for a row it stamped itself. This proves it for a row
            // the SERVICE deleted, which is the composition: allocator plus soft delete plus the
            // course row lock, in one transaction each.
            String first = createdQuestion();
            writes.delete(dana(), Message.request(Verb.QUESTION_DELETE,
                    new QuestionDeleteRequest(first, 1)));

            String second = createdQuestion();

            assertThat(second)
                    .as("a soft-deleted question still occupies its serial, so the next create "
                            + "must not be handed %s again", first)
                    .isNotEqualTo(first);
            assertThat(Integer.parseInt(second.substring(2)))
                    .as("and the next serial is the one after it, not a gap-filler")
                    .isEqualTo(Integer.parseInt(first.substring(2)) + 1);
        }
    }

    // ===================== The illustration, across an immutable edit =====

    @Nested
    @DisplayName("the illustration, through a real MEDIUMBLOB")
    class Illustration {

        /**
         * {@code QuestionService}'s {@code ImageAction.KEEP} path deliberately copies the blob
         * into version n+1 rather than sharing it, because ADR-011 says a version is immutable
         * and a shared blob is a version that can change under a released exam. That copy has
         * only ever moved a {@code byte[]} literal between two in-memory objects: the one real
         * blob round trip in the codebase is {@code EntityRoundTripTest}, whose own javadoc says
         * it runs on H2 and "says nothing about whether the mapping matches the real migrations".
         */
        @Test
        @DisplayName("⚑ KEEP copies the bytes into the new version, byte for byte")
        void keepCopiesTheBytesIntoTheNextVersion() {
            byte[] png = pngOf(512);
            Message created = create(new QuestionDraft(COURSE_ALGEBRA, TEXT, ANSWERS, 3, TOPIC,
                    Difficulty.MEDIUM, png));
            assertThat(created.isOk()).isTrue();
            String id = ((QuestionDetail) created.getPayload()).displayId5();

            Message edited = update(new QuestionEdit(id, 1, TEXT + " (מתוקן)", ANSWERS, 3, TOPIC,
                    Difficulty.MEDIUM, ImageAction.KEEP, null));
            assertThat(edited.isOk()).isTrue();

            QuestionImage v1 = image(id, 1);
            QuestionImage v2 = image(id, 2);

            assertThat(v1.bytes())
                    .as("what came out of the MEDIUMBLOB is what went in")
                    .isEqualTo(png);
            assertThat(v2.bytes())
                    .as("and KEEP copied it forward rather than losing it or sharing a reference")
                    .isEqualTo(png);
            assertThat(v1.contentType())
                    .as("sniffed from the stored bytes, not from anything the client declared")
                    .isEqualTo(QuestionImage.PNG);
            assertThat(v2.contentType()).isEqualTo(QuestionImage.PNG);
        }

        @Test
        @DisplayName("⚑ REMOVE on v3 leaves v1 and v2 still serving their image")
        void removeDoesNotReachBackIntoOlderVersions() {
            // ADR-011 expressed in bytes. If REMOVE cleared a shared blob rather than writing a
            // new version without one, the exam pinned to v1 would lose its illustration.
            byte[] png = pngOf(256);
            Message created = create(new QuestionDraft(COURSE_ALGEBRA, TEXT, ANSWERS, 3, TOPIC,
                    Difficulty.MEDIUM, png));
            String id = ((QuestionDetail) created.getPayload()).displayId5();
            update(new QuestionEdit(id, 1, TEXT, ANSWERS, 3, TOPIC, Difficulty.MEDIUM,
                    ImageAction.KEEP, null));

            Message removed = update(new QuestionEdit(id, 2, TEXT, ANSWERS, 3, TOPIC,
                    Difficulty.MEDIUM, ImageAction.REMOVE, null));
            assertThat(removed.isOk()).isTrue();

            assertThat(image(id, 1).bytes()).as("v1 keeps its illustration").isEqualTo(png);
            assertThat(image(id, 2).bytes()).as("so does v2").isEqualTo(png);
            Message v3 = reads.image(dana(),
                    Message.request(Verb.QUESTION_IMAGE_GET, new QuestionImageRequest(id, 3)));
            assertThat(v3.isOk())
                    .as("and v3, which she removed it from, has none to serve")
                    .isFalse();
            assertThat(v3.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    // ===================== Hebrew, through all three wire shapes ==========

    @Nested
    @DisplayName("Hebrew survives every field and every shape it is read back through")
    class HebrewRoundTrip {

        /**
         * Existing Hebrew coverage stops at the projection: {@code BankRepositoryContract} and
         * {@code BankBrowseMySqlTest} prove {@code text} and {@code topic} survive the column.
         * Nothing proves Hebrew reaches {@code QuestionDetail}, {@code QuestionVersionDetail} or
         * {@code BankQuestionRow}, and every bank fixture's ANSWERS are ASCII - so a1..a4 have
         * never carried Hebrew at all.
         */
        @Test
        @DisplayName("⚑ text, all four answers and the topic, read back three ways")
        void hebrewInEveryBearingFieldAndEveryShape() {
            String id = createdQuestion();

            QuestionDetail detail = get(id);
            assertThat(detail.text()).isEqualTo(TEXT);
            assertThat(detail.answers())
                    .as("a1..a4 in Hebrew - no existing fixture has ever put Hebrew here")
                    .containsExactlyElementsOf(ANSWERS);
            assertThat(detail.topic()).isEqualTo(TOPIC);

            QuestionVersionDetail fromHistory = versions(id).get(0);
            assertThat(fromHistory.text()).isEqualTo(TEXT);
            assertThat(fromHistory.answers()).containsExactlyElementsOf(ANSWERS);
            assertThat(fromHistory.topic()).isEqualTo(TOPIC);

            BankQuestionRow row = browse(null, null).rows().stream()
                    .filter(candidate -> candidate.displayId5().equals(id))
                    .findFirst().orElseThrow();
            assertThat(row.text()).isEqualTo(TEXT);
            assertThat(row.topic()).isEqualTo(TOPIC);
        }
    }

    // ===================== Browse, by identity rather than by count =======

    @Nested
    @DisplayName("what the service wrote is findable by what the browse builds")
    class Browse {

        /**
         * {@code BankBrowseContract} proves the filters against rows it persisted itself. This
         * proves the other half: that a question written through {@code QUESTION_CREATE} is
         * findable by each filter. The difficulty enum crosses the wire/entity boundary three
         * times on that path, and the topic filter is exact equality under the collation.
         */
        @Test
        @DisplayName("⚑ found by the exact Hebrew topic, and by difficulty, asserted by identity")
        void foundByEachFilterIndependently() {
            String id = createdQuestion();

            assertThat(rowsIn(browse(TOPIC, null)))
                    .as("exact Hebrew topic")
                    .contains(id);
            assertThat(rowsIn(browse(null, Difficulty.MEDIUM)))
                    .as("difficulty, which crosses wire enum -> entity enum -> wire enum")
                    .contains(id);
            assertThat(rowsIn(browse(TOPIC, Difficulty.MEDIUM)))
                    .as("and both together")
                    .contains(id);

            // The negative, which is what makes the three above mean anything: a count-based
            // assertion would pass while the filter matched everything.
            assertThat(rowsIn(browse(TOPIC + "ים", null)))
                    .as("a near-miss topic finds nothing - the filter is equality, not a prefix")
                    .doesNotContain(id);
            assertThat(rowsIn(browse(null, Difficulty.HARD)))
                    .as("and the wrong difficulty finds nothing")
                    .doesNotContain(id);
        }
    }

    // ===================== A refusal writes nothing =======================

    @Nested
    @DisplayName("a refused create leaves the database exactly as it was")
    class RefusalsWriteNothing {

        @Test
        @DisplayName("⚑ counted before and after, not asserted against a mock")
        void aRefusedCreateWritesNoRows() {
            // QuestionServiceTest asserts verify(session, never()).persist(any()) - which cannot
            // see a partial flush, a failed rollback, or a row written by the allocator before the
            // validator ran. Counting the tables is the assertion that survives all three.
            long questionsBefore = countRows("questions");
            long versionsBefore = countRows("question_versions");

            Message refused = create(draft(List.of("שווה", "שווה", "אחר", "שונה"), 1));

            assertThat(refused.isOk()).isFalse();
            assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(countRows("questions"))
                    .as("a refused create must not have allocated a question row")
                    .isEqualTo(questionsBefore);
            assertThat(countRows("question_versions"))
                    .as("nor written a version")
                    .isEqualTo(versionsBefore);
        }
    }

    // ===================== Reads, in their own transactions ===============

    /** Creates the standard question and hands back its display id. */
    private String createdQuestion() {
        Message created = create(draft(ANSWERS, 3));
        assertThat(created.isOk())
                .as("the fixture create should have been accepted")
                .isTrue();
        return ((QuestionDetail) created.getPayload()).displayId5();
    }

    /** Re-reads one question through {@code QUESTION_GET}, in a fresh transaction. */
    private QuestionDetail get(String displayId5) {
        Message answer = reads.get(dana(),
                Message.request(Verb.QUESTION_GET, new QuestionRequest(displayId5)));
        assertThat(answer.isOk())
                .as("QUESTION_GET should have answered OK for %s", displayId5)
                .isTrue();
        return (QuestionDetail) answer.getPayload();
    }

    /** The whole history through {@code QUESTION_VERSIONS}, newest first. */
    private List<QuestionVersionDetail> versions(String displayId5) {
        Message answer = reads.versions(dana(),
                Message.request(Verb.QUESTION_VERSIONS, new QuestionRequest(displayId5)));
        assertThat(answer.isOk())
                .as("QUESTION_VERSIONS should have answered OK for %s", displayId5)
                .isTrue();
        return ((VersionHistory) answer.getPayload()).versions();
    }

    /** One page of the bank through {@code BANK_LIST}, filtered. */
    private BankPage browse(String topic, Difficulty difficulty) {
        Message answer = reads.list(dana(), Message.request(Verb.BANK_LIST,
                new BankListRequest(COURSE_ALGEBRA, topic, difficulty, null, 0,
                        BankListRequest.DEFAULT_PAGE_SIZE)));
        assertThat(answer.isOk()).as("BANK_LIST should have answered OK").isTrue();
        return (BankPage) answer.getPayload();
    }

    private static List<String> rowsIn(BankPage page) {
        return page.rows().stream().map(BankQuestionRow::displayId5).toList();
    }

    /** One version's illustration through {@code QUESTION_IMAGE_GET}, in a fresh transaction. */
    private QuestionImage image(String displayId5, int versionNo) {
        Message answer = reads.image(dana(), Message.request(Verb.QUESTION_IMAGE_GET,
                new QuestionImageRequest(displayId5, versionNo)));
        assertThat(answer.isOk())
                .as("QUESTION_IMAGE_GET should have answered OK for %s v%d", displayId5, versionNo)
                .isTrue();
        return (QuestionImage) answer.getPayload();
    }

    /**
     * A byte array the sniffer accepts as PNG.
     *
     * <p>The eight-byte signature and then filler: {@code QuestionImages.sniff} reads the
     * signature and nothing else, so a real encoder would add bytes without adding coverage. The
     * filler is varied rather than zeroed so a truncated column or a mangled copy shows up as a
     * difference rather than as one run of identical bytes matching another.
     */
    private static byte[] pngOf(int length) {
        byte[] png = new byte[length];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A,
                (byte) 0x1A, (byte) 0x0A};
        System.arraycopy(signature, 0, png, 0, signature.length);
        for (int i = signature.length; i < length; i++) {
            png[i] = (byte) (i * 31 % 251);
        }
        return png;
    }

    /** Counts a table in its own transaction, so it sees only committed rows. */
    private long countRows(String table) {
        return inTx(session -> session
                .createNativeQuery("SELECT COUNT(*) FROM " + table, Long.class)
                .getSingleResult());
    }
}
