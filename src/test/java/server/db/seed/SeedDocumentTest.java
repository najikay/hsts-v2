package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser's own tests (E2.15).
 *
 * <p>{@link SeedDocument} is about to become the single source of truth for two people's
 * checks, so it is the last place to take "it looked right" as evidence. <b>A parser that is
 * not itself tested is a confident liar:</b> the throwaway script used to verify PR 3a's
 * re-transcription produced three false positives before it produced a true negative, and each
 * one looked exactly like a transcription error until it was read closely. Those three are the
 * fixtures below.
 *
 * <p>Two kinds of test here. The first reads the real document and pins what it contains, so a
 * reformat that breaks parsing fails loudly rather than quietly returning less. The second
 * feeds deliberately broken documents from {@link TempDir} and proves the failure modes are
 * failures, because the rule is that silence is never a pass.
 */
class SeedDocumentTest {

    private static SeedDocument real() {
        return SeedDocument.read();
    }

    // ------------------------------------------- what the real document holds

    @Test
    @DisplayName("every section parses, and none of them is silently empty")
    void everySectionYieldsRows() {
        SeedDocument document = real();

        assertThat(document.subjects()).hasSize(2);
        assertThat(document.courses()).hasSize(4);
        assertThat(document.users()).hasSize(18);
        assertThat(document.coordinators()).hasSize(2);
        assertThat(document.questions()).hasSize(40);
        assertThat(document.exams()).hasSize(6);
        assertThat(document.examTexts()).hasSize(6);
        assertThat(document.grades(1)).hasSize(8);
        assertThat(document.notifications()).hasSize(8);
    }

    @Test
    @DisplayName("the struck-through roster row is skipped, not read as a teacher")
    void courseTeachersSkipsTheDashedRow() {
        // The 2026-08-20 roster change left rina.barak's Calculus row in the table with a dash
        // rather than deleting it, so the history stays readable. A dash means "no teacher on
        // this line", which is not the same as a missing cell, and reading it as a teacher
        // named "-" would put a nonexistent user in the expectations.
        List<SeedDocument.TeachesRow> teaches = real().courseTeachers();

        assertThat(teaches).hasSize(5);
        assertThat(teaches).noneMatch(row -> row.teacher().equals("rina.barak"));
        assertThat(teaches).filteredOn(row -> row.course().equals("12"))
                .extracting(SeedDocument.TeachesRow::teacher)
                .containsExactly("dana.cohen");
    }

    @Test
    @DisplayName("enrollments flatten to one row per pair, matching the stated per-course totals")
    void enrollmentsFlatten() {
        List<SeedDocument.EnrollmentRow> enrolled = real().enrollments();

        assertThat(enrolled).hasSize(29);
        assertThat(countCourse(enrolled, "11")).isEqualTo(8);
        assertThat(countCourse(enrolled, "12")).isEqualTo(6);
        assertThat(countCourse(enrolled, "21")).isEqualTo(8);
        assertThat(countCourse(enrolled, "22")).isEqualTo(7);
    }

    @Test
    @DisplayName("a numbered reference resolves to the username, never the document's id")
    void referencesResolveToUsernames() {
        // "3 rina.barak" is a document-internal id plus the stable key. The id is not a
        // database id and must never be treated as one; see SeedSection.
        assertThat(real().coordinators())
                .extracting(SeedDocument.CoordinatorRow::teacher)
                .containsExactlyInAnyOrder("rina.barak", "michal.sharon");
    }

    @Test
    @DisplayName("section 8.2's second table is found, not just its first")
    void rejectionReasonsAreReadFromTheSecondTable() {
        // §8.2 holds the per-exam texts first and the rejections after a paragraph of prose.
        // An accessor that read "the first table in §8.2" would see only the texts and the two
        // rejection reasons would be stored but never checked. Found by counting the
        // document's rows, not by reading the parser.
        List<SeedDocument.RejectionRow> rejections = real().rejectionReasons();

        assertThat(rejections).hasSize(2);
        assertThat(real().examTexts()).as("the first table is still read as the texts").hasSize(6);

        assertThat(rejections).extracting(SeedDocument.RejectionRow::exam)
                .containsExactlyInAnyOrder(1, 5);
        assertThat(rejections).allSatisfy(row ->
                assertThat(row.examVersion()).isEqualTo(1));
    }

    @Test
    @DisplayName("a reference with a trailing gloss still resolves to the username")
    void referencesIgnoreATrailingParenthetical() {
        // §8.2 writes "3 rina.barak (coordinator of subject 10)". The parenthetical is a note
        // for the reader; the username is the key.
        assertThat(real().rejectionReasons())
                .extracting(SeedDocument.RejectionRow::rejectedBy)
                .containsExactlyInAnyOrder("rina.barak", "michal.sharon");
    }

    // ----------------------------------------------- the load-bearing semantics

    @Test
    @DisplayName("the v1 marker in section 8.1 is read as a pin, not stripped as bold")
    void theCompositionPinSurvives() {
        // The Algebra Midterm references question 11005 at version 1 while version 2 exists in
        // the bank. That row is what exercises the composite foreign key. A parser that treated
        // ** as decoration would return it as an ordinary slot and every check built on it
        // would stop testing the thing it names.
        List<SeedDocument.CompositionRow> composition = real().composition();

        assertThat(composition).hasSize(39);

        List<SeedDocument.CompositionRow> pinned = composition.stream()
                .filter(row -> row.pinnedVersion() != null)
                .toList();

        assertThat(pinned).hasSize(2);
        assertThat(pinned).allSatisfy(row -> {
            assertThat(row.question()).isEqualTo("11005");
            assertThat(row.pinnedVersion()).isEqualTo(1);
            assertThat(row.exam()).isEqualTo(1);
        });
        assertThat(pinned).extracting(SeedDocument.CompositionRow::examVersion)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("an unpinned slot is null, never defaulted to version 1")
    void unpinnedSlotsStayNull() {
        // "pinned to v1" and "whatever is latest" are different statements. Defaulting one to
        // the other would make the pin unremarkable and lose the distinction the document draws.
        assertThat(real().composition())
                .filteredOn(row -> !row.question().equals("11005"))
                .allSatisfy(row -> assertThat(row.pinnedVersion()).isNull());
    }

    @Test
    @DisplayName("every exam version's points total 100, read straight from the document")
    void compositionPointsTotalOneHundred() {
        real().composition().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> row.exam() + "v" + row.examVersion(),
                        java.util.stream.Collectors.summingInt(SeedDocument.CompositionRow::points)))
                .forEach((version, total) ->
                        assertThat(total).as("exam %s", version).isEqualTo(100));
    }

    @Test
    @DisplayName("a dash in 9.1.1 means no row, not a null selection")
    void aDashMeansAbsentNotNull() {
        // omer.katz timed out with four questions never reached. Absent from attempt_answers
        // and answered-wrongly are different things, and his is the only attempt that
        // distinguishes them, which makes it H12.4's fixture.
        List<SeedDocument.SelectionRow> selections = real().selections(1);

        assertThat(selections).hasSize(56);

        List<SeedDocument.SelectionRow> omer = selections.stream()
                .filter(row -> row.student().equals("omer.katz"))
                .toList();

        assertThat(omer).hasSize(7);
        assertThat(omer).filteredOn(row -> !row.answered()).hasSize(4);
        assertThat(omer).filteredOn(SeedDocument.SelectionRow::answered).hasSize(3);
    }

    @Test
    @DisplayName("every other student answered all seven")
    void onlyTheTimedOutAttemptHasAbsentRows() {
        assertThat(real().selections(1))
                .filteredOn(row -> !row.answered())
                .extracting(SeedDocument.SelectionRow::student)
                .containsOnly("omer.katz");
    }

    @Test
    @DisplayName("the exam status column is read, not required-and-ignored")
    void examVersionStatusesAreParsed() {
        List<SeedDocument.ExamVersionStatusRow> statuses = real().examVersionStatuses();

        assertThat(statuses).hasSize(7);
        assertThat(statuses).filteredOn(row -> row.exam() == 1)
                .extracting(SeedDocument.ExamVersionStatusRow::status)
                .containsExactly("REJECTED", "APPROVED");
        assertThat(statuses).extracting(SeedDocument.ExamVersionStatusRow::status)
                .containsOnly("DRAFT", "PENDING", "APPROVED", "REJECTED");
    }

    @Test
    @DisplayName("selections are pinned by value, not only by how many there are")
    void selectionValuesArePinned() {
        // Size-and-flags assertions leave every selected option unchecked, and these two
        // accessors are what SeedDatasetContract recomputes scores from, so an unguarded
        // value here becomes an unguarded input there. lior.gabay answered all seven
        // correctly for 100; omer.katz reached only the first three.
        assertThat(selectionsOf("lior.gabay"))
                .containsExactly("1", "2", "1", "3", "1", "2", "3");
        assertThat(selectionsOf("omer.katz"))
                .containsExactly("1", "2", "1", "-", "-", "-", "-");
    }

    @Test
    @DisplayName("grades are pinned by value too")
    void gradeValuesArePinned() {
        SeedDocument.GradeRow lior = grade("lior.gabay");
        SeedDocument.GradeRow yael = grade("yael.azulay");
        SeedDocument.GradeRow omer = grade("omer.katz");

        assertThat(lior.auto()).isEqualTo(100);
        assertThat(lior.finalScore()).isEqualTo(100);
        // The one manual override in the seed: 45 auto, 55 final.
        assertThat(yael.auto()).isEqualTo(45);
        assertThat(yael.finalScore()).isEqualTo(55);
        assertThat(omer.attemptStatus()).isEqualTo("TIMED_OUT");
    }

    // ------------------------------------------------- sections 9 and 10

    @Test
    @DisplayName("the four executions parse, including which exam version each releases")
    void executionsParse() {
        List<SeedDocument.ExecutionRow> executions = real().executions();

        assertThat(executions).hasSize(4);
        assertThat(executions).extracting(SeedDocument.ExecutionRow::code)
                .containsExactly("4821", "7390", "5164", "2075");
        assertThat(executions).extracting(SeedDocument.ExecutionRow::status)
                .containsExactly("CLOSED", "CLOSED", "SCHEDULED", "LIVE");

        // Executions 1 and 4 release the SAME exam version. That is S-2, and it is why
        // exam_executions carries no participation counters: two releases of one version must
        // not contaminate each other's numbers.
        assertThat(executions.get(0).exam()).isEqualTo(1);
        assertThat(executions.get(0).examVersion()).isEqualTo(2);
        assertThat(executions.get(3).exam()).isEqualTo(1);
        assertThat(executions.get(3).examVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("execution 2's grades have no final score, and that is not zero")
    void gradesAwaitingApprovalHaveNoFinalScore() {
        // §9.2 writes a dash: nothing has been approved, so there is no final score. A parser
        // returning 0 would make eight students look like they had all failed.
        List<SeedDocument.GradeRow> awaiting = real().grades(2);

        assertThat(awaiting).hasSize(8);
        assertThat(awaiting).allSatisfy(row -> assertThat(row.finalScore()).isNull());
        assertThat(awaiting).extracting(SeedDocument.GradeRow::auto)
                .containsExactly(100, 85, 75, 70, 60, 55, 40, 30);

        // §9.1's are approved and every one carries a final score.
        assertThat(real().grades(1)).allSatisfy(row ->
                assertThat(row.finalScore()).isNotNull());
    }

    @Test
    @DisplayName("execution 2's grid has no absent rows: nobody timed out")
    void executionTwoSelectionsAreComplete() {
        // The contrast that makes omer.katz's four dashes in §9.1.1 meaningful. If both grids
        // were full, absent-versus-wrong would have no fixture at all.
        List<SeedDocument.SelectionRow> selections = real().selections(2);

        assertThat(selections).hasSize(56);
        assertThat(selections).allMatch(SeedDocument.SelectionRow::answered);
    }

    @Test
    @DisplayName("the four bots parse, and bot 4's reason does not flip its flag")
    void botsParse() {
        List<SeedDocument.BotRow> bots = real().bots();

        assertThat(bots).hasSize(4);
        assertThat(bots).extracting(SeedDocument.BotRow::course)
                .containsExactly("11", "12", "21", "22");
        // Bot 4's cell reads "**no** - inactive, for the S-31 refusal demo". The explanation
        // is longer than the value and must not change what the value means.
        assertThat(bots).extracting(SeedDocument.BotRow::active)
                .containsExactly(true, true, true, false);
    }

    @Test
    @DisplayName("all eight sources parse with a real body")
    void botSourcesParse() {
        // These are the longest hand-transcribed strings in the seed, so an unparsed source
        // would mean the one place a slip is most likely goes unchecked.
        List<SeedDocument.BotSourceRow> sources = real().botSources();

        assertThat(sources).hasSize(8);
        assertThat(sources).extracting(SeedDocument.BotSourceRow::bot)
                .containsExactly(1, 1, 2, 2, 3, 3, 4, 4);
        assertThat(sources).allSatisfy(row -> {
            assertThat(row.title()).isNotBlank();
            assertThat(row.body().length())
                    .as("source %d body", row.number()).isGreaterThan(100);
        });
    }

    @Test
    @DisplayName("the source type labels contradict section 10's own ruling, and this records it")
    void sourceLabelsContradictTheRuling() {
        // §10's preamble: "all eight seeded sources are type = TEXT". Five labels say
        // otherwise. Both are legal enum values, so a mechanical transcription would store
        // five wrong types and nothing would fail. Pinned here so the contradiction is a
        // stated fact rather than something the next reader rediscovers.
        assertThat(real().botSources()).extracting(SeedDocument.BotSourceRow::type)
                .containsExactly("TEXT", "PDF", "TEXT", "PDF", "DOCX", "PDF", "TEXT", "PDF");
    }

    @Test
    @DisplayName("the eight sessions parse, one provider among them being anthropic")
    void botSessionsParse() {
        List<SeedDocument.BotSessionRow> sessions = real().botSessions();

        assertThat(sessions).hasSize(8);
        // Exactly one anthropic row: without it the provider column is a constant and the
        // ADR-009 fallback chain demonstrates nothing.
        assertThat(sessions).filteredOn(row -> row.provider().equals("anthropic")).hasSize(1);
        assertThat(sessions).filteredOn(row -> row.provider().equals("deepseek")).hasSize(7);
        // Bot 4 is inactive, so S-31 forbids any session on it.
        assertThat(sessions).extracting(SeedDocument.BotSessionRow::bot).doesNotContain(4);
    }

    // ------------------------------ the three that fooled the throwaway script

    @Test
    @DisplayName("emphasis is stripped but a literal asterisk is not")
    void countStarSurvivesWhileEmphasisGoes() {
        // Question 22001's stem holds both: *before* is markdown, COUNT(*) is SQL. The
        // throwaway removed every asterisk, turned COUNT(*) into COUNT() and reported a
        // transcription error that did not exist.
        SeedDocument.QuestionRow before = question("22001");
        SeedDocument.QuestionRow count = question("22003");

        assertThat(before.text()).isEqualTo("Which clause filters rows before grouping?");
        assertThat(before.text()).doesNotContain("*");
        assertThat(count.text()).contains("COUNT(*)");
    }

    @Test
    @DisplayName("backticks are stripped wherever they appear, not only at the edges")
    void backticksGoFromTheMiddleToo() {
        // The throwaway anchored its strip to the start and end of the cell, so a stem like
        // "A runtime `AmbiguousMethodError`" kept an inner backtick and never matched.
        assertThat(question("21003").a4()).isEqualTo("A runtime AmbiguousMethodError");
        assertThat(real().questions()).allSatisfy(row -> {
            assertThat(row.text()).doesNotContain("`");
            row.options().forEach(option -> assertThat(option).doesNotContain("`"));
        });
    }

    @Test
    @DisplayName("the house rule accepts all three replacements the PRD permits")
    void houseRuleAcceptsCommaPeriodAndColon() {
        // PRD 4.1 permits a comma, a period or a colon, and the loader uses different ones
        // deliberately: a colon reads correctly in a title, a comma in a sentence. A predicate
        // that insisted on the comma form would be stricter than the rule it enforces, and an
        // earlier version of this one failed every exam title for exactly that reason.
        assertThat(SeedDocument.followsHouseRule("Nothing — it is safe", "Nothing, it is safe"))
                .isTrue();
        assertThat(SeedDocument.followsHouseRule("מבחן אמצע — אלגברה", "מבחן אמצע: אלגברה"))
                .isTrue();
        assertThat(SeedDocument.followsHouseRule("Draft — needs work", "Draft. needs work"))
                .isTrue();
    }

    @Test
    @DisplayName("the house rule also collapses the space that preceded the dash")
    void houseRuleHandlesTheSurroundingSpace() {
        // "Nothing - it is safe" became "Nothing , it is safe" in the throwaway, because it
        // replaced the dash and left the space in front of it standing.
        assertThat(SeedDocument.followsHouseRule("Nothing — it is safe", "Nothing , it is safe"))
                .isFalse();
    }

    @Test
    @DisplayName("the house rule is not a licence to change anything else")
    void houseRuleRejectsUnrelatedEdits() {
        // Without this the predicate could drift into "close enough", which would let a real
        // transcription error through in any string that happens to contain an em dash.
        assertThat(SeedDocument.followsHouseRule("Nothing — it is safe", "Nothing, it is unsafe"))
                .isFalse();
        assertThat(SeedDocument.followsHouseRule("no dash here", "no dash here")).isTrue();
        assertThat(SeedDocument.followsHouseRule("no dash here", "no dash HERE")).isFalse();
    }

    @Test
    @DisplayName("the parser reports the document verbatim and leaves the house rule to callers")
    void theParserDoesNotApplyTheHouseRule() {
        // Separation of concerns worth pinning: this class says what the document says. What
        // the loader stores is the loader's policy, applied deliberately by the consumer.
        assertThat(question("21008").options())
                .anySatisfy(option -> assertThat(option).contains("—"));
    }

    // ---------------------------------------------- silence is never a pass

    @Test
    @DisplayName("a missing section fails, and says which one")
    void aMissingSectionFails(@TempDir Path dir) throws IOException {
        Path document = write(dir, "## 1. Subjects\n\n| code2 | name |\n|---|---|\n| 10 | Maths |\n");

        assertThatThrownBy(() -> SeedDocument.read(document).courses())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("## 2.")
                .hasMessageContaining("not in the document");
    }

    @Test
    @DisplayName("a section with a header but no data rows fails")
    void anEmptyTableFails(@TempDir Path dir) throws IOException {
        // This is the rule that matters most. A parser answering "no rows" makes every
        // assertion built on it pass vacuously, which is how the unreachable auto-scores
        // survived review.
        Path document = write(dir, "## 1. Subjects\n\n| code2 | name |\n|---|---|\n\n## 2. End\n");

        assertThatThrownBy(() -> SeedDocument.read(document).subjects())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no data rows")
                .hasMessageContaining("never a silent pass");
    }

    @Test
    @DisplayName("a section with no table at all fails")
    void aSectionWithoutATableFails(@TempDir Path dir) throws IOException {
        Path document = write(dir, "## 1. Subjects\n\nJust prose, no table.\n\n## 2. End\n");

        assertThatThrownBy(() -> SeedDocument.read(document).subjects())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holds no table");
    }

    @Test
    @DisplayName("a row with too few columns fails rather than being skipped")
    void aMalformedRowFails(@TempDir Path dir) throws IOException {
        // Skipping it would mean a reformat silently shrinks what is checked.
        Path document = write(dir,
                "## 1. Subjects\n\n| code2 | name |\n|---|---|\n| 10 | Maths |\n| 20 |\n");

        assertThatThrownBy(() -> SeedDocument.read(document).subjects())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cells where its header has");
    }

    @Test
    @DisplayName("a composition cell whose format changed fails, naming the expected shape")
    void aReformattedCompositionCellFails(@TempDir Path dir) throws IOException {
        // The markdown shape is part of the contract: reformatting is a deliberate change made
        // in the same commit as this parser, or the build goes red by design.
        Path document = write(dir, "### 8.1 Composition\n\n"
                + "| exam | version | duration | questions |\n|---|---|---|---|\n"
                + "| 1 | v1 | 60 min | eleven thousand and one, twenty points |\n");

        assertThatThrownBy(() -> SeedDocument.read(document).composition())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listed no questions");
    }

    @Test
    @DisplayName("a missing file fails with the path, not a NullPointerException")
    void aMissingFileFails(@TempDir Path dir) {
        assertThatThrownBy(() -> SeedDocument.read(dir.resolve("nope.md")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("an empty file fails")
    void anEmptyFileFails(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> SeedDocument.read(write(dir, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    // ---------------------------------------------------------------- helpers

    private static SeedDocument.QuestionRow question(String displayId) {
        return real().questions().stream()
                .filter(row -> row.displayId().equals(displayId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no question " + displayId));
    }

    /** @return one student's seven selections in question order, a dash for the absent ones */
    private static List<String> selectionsOf(String student) {
        return real().selections(1).stream()
                .filter(row -> row.student().equals(student))
                .map(row -> row.answered() ? String.valueOf(row.selected()) : "-")
                .toList();
    }

    private static SeedDocument.GradeRow grade(String student) {
        return real().grades(1).stream()
                .filter(row -> row.student().equals(student))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no grade for " + student));
    }

    private static long countCourse(List<SeedDocument.EnrollmentRow> rows, String course) {
        return rows.stream().filter(row -> row.course().equals(course)).count();
    }

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("SEED_CONTENT.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
