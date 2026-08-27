package server.db.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one reader of {@code docs/seed/SEED_CONTENT.md} (E2.15).
 *
 * <h2>One reader, no assertions</h2>
 *
 * <p>This class parses and nothing else. It compares nothing, expects nothing and knows nothing
 * about the database. Two consumers do the comparing: {@code SeedLoadedDbTest} checks that the
 * loaded database matches what this returns, and {@code SeedDatasetContract} recomputes every
 * score from it. Two readings of one document is the drift risk one level up from two copies of
 * the data, which is why there is exactly one of these.
 *
 * <p><b>Name corrected 2026-08-26.</b> The recomputing half was cited here as
 * {@code SeedArithmeticTest} for as long as this javadoc has existed, and no such class was ever
 * written; the arithmetic assertions live in {@code SeedDatasetContract}. A citation to a class
 * that does not exist reads as coverage on a cold review, which is the only reason it is worth a
 * line.
 *
 * <h2>Silence is an error, never a pass</h2>
 *
 * <p>Every accessor throws when its section is missing, when the section holds no table, or when
 * the table holds zero data rows. That is deliberate and it is the whole point: a check that
 * quietly matches nothing is how §9's unreachable auto-scores survived review. A parser that
 * answers "no rows" lets every assertion built on it pass vacuously, so this one refuses to.
 *
 * <h2>The markdown shape is part of the contract</h2>
 *
 * <p>Reformatting a table in the document is a deliberate contract change, made in the same
 * commit as the expectation here, or the build goes red by design. That is correct behaviour
 * rather than fragility: the alternative is a reformat that silently stops being checked.
 *
 * <h2>Normalisation is per field, because markdown here is not always decoration</h2>
 *
 * <p>A blanket "strip the punctuation" pass would destroy meaning in three places, so there is
 * no such pass:
 *
 * <ul>
 *   <li><b>{@code **v1**} in §8.1 is a pin, not bold.</b> It says the Algebra Midterm references
 *       question 11005 at version 1 while version 2 exists in the bank. {@link #composition()}
 *       returns it as {@link CompositionRow#pinnedVersion()}, and losing it would silently turn
 *       the row that exercises the composite foreign key into an ordinary one.</li>
 *   <li><b>{@code —} in §9.1.1 means no row at all</b>, not a null selection. A question the
 *       student never reached is absent from {@code attempt_answers}; a question answered wrongly
 *       is present. {@code omer.katz} is the only attempt that distinguishes the two, and that
 *       distinction is H12.4's fixture. {@link SelectionRow#answered()} keeps them apart.</li>
 *   <li><b>A lone {@code *} can be literal.</b> Question 22001 writes {@code *before*}
 *       (emphasis, to strip) and question 22003 writes {@code COUNT(*)} (SQL, to keep).
 *       Emphasis is therefore removed only as a matched pair around non-space text. A throwaway version of
 *       this parser stripped every asterisk, turned {@code COUNT(*)} into {@code COUNT()} and
 *       reported a transcription error that did not exist.</li>
 * </ul>
 *
 * <p>The house rule that replaces em dashes in stored text is <b>not</b> applied here. This
 * class reports what the document says; what the loader does with it is the loader's policy, and
 * {@link #houseRule(String)} is where a consumer applies it deliberately.
 */
public final class SeedDocument {

    /** Where the document lives, relative to the project root that tests run from. */
    private static final Path DEFAULT_PATH = Path.of("docs", "seed", "SEED_CONTENT.md");

    /** A markdown table row: starts with a pipe. Separator rows are dropped. */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    /**
     * A markdown separator, needing a real run of hyphens.
     *
     * <p>{@code [\s:|-]+} alone also matches {@code | - | - | - |}, which is a legal way to
     * strike out a row and which §4 already writes with an em dash. Such a row would have been
     * eaten as punctuation rather than read as data.
     */
    private static final Pattern SEPARATOR_ROW =
            Pattern.compile("^\\s*\\|[\\s:|-]*-{3,}[\\s:|-]*\\|\\s*$");

    /** Emphasis, but only as a matched pair around non-space text. See the class javadoc. */
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)");

    /** A composition slot: {@code 11005 **v1**->20} or {@code 11001->20}. */
    private static final Pattern SLOT = Pattern.compile(
            "(\\d{5})\\s*(?:\\*\\*v(\\d+)\\*\\*)?\\s*(?:\u2192|->)\\s*(\\d+)");

    /** {@code v1 **REJECTED**} in §8's status column. The bold is decoration here. */
    private static final Pattern VERSION_STATUS =
            Pattern.compile("v(\\d+)\\s*\\*\\*([A-Z_]+)\\*\\*");

    /** {@code **Source 1** · bot 1 · TEXT · `title`}, §10.1's per-source heading line. */
    private static final Pattern SOURCE_HEADER = Pattern.compile(
            "^\\*\\*Source (\\d+)\\*\\*\\s*·\\s*bot (\\d+)\\s*·\\s*(\\w+)\\s*·\\s*(.+)$");

    /** A "3 rina.barak" style reference: the number is the document's, the name is the key. */
    private static final Pattern REFERENCE = Pattern.compile("^\\s*\\d+\\s+([A-Za-z0-9._]+)\\s*$");

    private final List<String> lines;

    private SeedDocument(List<String> lines) {
        this.lines = lines;
    }

    /** @return the document at its usual place under the project root */
    public static SeedDocument read() {
        return read(DEFAULT_PATH);
    }

    /**
     * @param path the document to read
     * @return the parsed document
     * @throws IllegalStateException when the file is absent or empty
     */
    public static SeedDocument read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("seed document not found at " + path.toAbsolutePath()
                    + ". Tests run from the project root, so this is a path problem or the "
                    + "document has moved.");
        }
        try {
            List<String> read = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (read.isEmpty()) {
                throw new IllegalStateException("seed document is empty: " + path);
            }
            return new SeedDocument(read);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    // ------------------------------------------------------------------ rows

    /** §1. */
    public record SubjectRow(String code, String name) { }

    /** §2. */
    public record CourseRow(String code, String subject, String name) { }

    /** §3. The document's leading id is deliberately not exposed; see SeedSection. */
    public record UserRow(String username, String fullName, String role, String nationalId) { }

    /** §4, one row per pair. */
    public record TeachesRow(String course, String teacher) { }

    /** §5. */
    public record CoordinatorRow(String subject, String teacher) { }

    /** §6, flattened to one row per (student, course) pair. */
    public record EnrollmentRow(String student, String course) { }

    /** §7.1 to §7.4. */
    public record QuestionRow(String displayId, String topic, String difficulty, String text,
                              String a1, String a2, String a3, String a4,
                              int correct, boolean illustrated) {

        /** @return the four options in document order, 1-based index {@code n} at {@code n-1} */
        public List<String> options() {
            return List.of(a1, a2, a3, a4);
        }

        /** @return the text of the option the document marks correct */
        public String correctOption() {
            return options().get(correct - 1);
        }
    }

    /** §8. */
    public record ExamRow(int number, String displayId, String course, String name,
                          String author) { }

    /**
     * §8.1, flattened to one row per slot.
     *
     * @param pinnedVersion the question version this exam version names explicitly, or
     *                      {@code null} when the document asks for the latest. Never silently
     *                      defaulted to 1: "pinned to v1" and "whatever is latest" are different
     *                      statements and only one of them exercises the composite foreign key.
     */
    public record CompositionRow(int exam, int examVersion, int durationMinutes,
                                 String question, Integer pinnedVersion, int points,
                                 int ordinal) { }

    /** §8.2. */
    public record ExamTextRow(int exam, String studentText, String teacherText) { }

    /** §8's last column, one row per exam version. */
    public record ExamVersionStatusRow(int exam, int examVersion, String status) { }

    /** §8.2's second table. */
    public record RejectionRow(int exam, int examVersion, String rejectedBy, String reason) { }

    /** §9's execution table. Windows are deliberately not parsed; see {@link #executions()}. */
    public record ExecutionRow(int number, int exam, int examVersion, String code,
                               String status) { }

    /**
     * §9.1 and §9.2.
     *
     * @param finalScore the final column, or {@code null} where §9.2 writes a dash because no
     *                   grade has been approved yet. Absent and zero are different things.
     */
    public record GradeRow(String student, String attemptStatus, int solvingMinutes,
                           int auto, Integer finalScore) { }

    /**
     * §9.1's manual override, the one grade in the seed a teacher changed by hand.
     *
     * @param reason         the explanation T-8.3 requires, shown on the grade review screen
     * @param teacherComment the sentence written for the student to read (S-22)
     */
    public record OverrideRow(String reason, String teacherComment) { }

    /** §10's bot table. */
    public record BotRow(int number, String course, String name, boolean active) { }

    /** §10.1, which is prose blocks rather than a table. */
    public record BotSourceRow(int number, int bot, String type, String title, String body) { }

    /** §10.2, where one row is a session and its single message. */
    public record BotSessionRow(int number, int bot, String student, int daysAgo,
                                String provider, String question, String answer) { }

    /**
     * §9.1.1, one row per (student, question) cell.
     *
     * @param answered {@code false} when the document shows a dash, meaning <b>no row</b> in
     *                 {@code attempt_answers} rather than a row with a null selection
     * @param selected the option the student chose, meaningful only when {@code answered}
     */
    public record SelectionRow(String student, String question, boolean answered, int selected) { }

    /**
     * §11.
     *
     * @param seedId the document's stable identifier for this row, added in the 2026-08-20
     *               amendment. The database has no column for it, so it is not compared against
     *               anything; it exists so a failure can name <em>which</em> notification
     *               disagreed instead of quoting a Hebrew title back at the reader.
     */
    public record NotificationRow(String seedId, int number, String recipient, String type,
                                  String title, boolean read) { }

    // -------------------------------------------------------------- sections

    /** @return §1's two subjects */
    public List<SubjectRow> subjects() {
        return map(rows("## 1.", 2), cells ->
                new SubjectRow(plain(cells[0]), plain(cells[1])));
    }

    /** @return §2's four courses */
    public List<CourseRow> courses() {
        return map(rows("## 2.", 3), cells ->
                new CourseRow(plain(cells[0]), plain(cells[1]), plain(cells[2])));
    }

    /** @return §3's eighteen users */
    public List<UserRow> users() {
        return map(rows("## 3.", 6), cells -> new UserRow(
                plain(cells[1]), plain(cells[2]), plain(cells[3]), plain(cells[4])));
    }

    /**
     * §4, skipping rows whose teacher cell is a dash.
     *
     * <p>The roster change of 2026-08-20 left a struck-through row in the table rather than
     * deleting it, so that the history stays readable. A dash there means "no teacher on this
     * line".
     *
     * <p><b>An empty cell is skipped too, and that is {@link #isDash}'s behaviour rather than a
     * decision taken here.</b> This paragraph claimed the two were distinguished until
     * 2026-08-27; they are not, so a cell blanked by accident reads as a deliberately empty
     * roster line instead of failing the parse. Contrast §9.1.1's grid, which does draw the
     * distinction and requires a blank to be written as a dash, because there an invented
     * "never reached" would move a student's score. Here it drops a teaching assignment, which
     * {@code SeedLoadedDbContract.facultyMatches} would catch on the next run.
     *
     * @return one row per (course, teacher) pair that names a teacher
     */
    public List<TeachesRow> courseTeachers() {
        List<TeachesRow> teaches = new ArrayList<>();
        for (String[] cells : rows("## 4.", 3)) {
            String teacher = cells[1];
            if (isDash(teacher)) {
                continue;
            }
            teaches.add(new TeachesRow(code(cells[0]), reference(teacher)));
        }
        require(!teaches.isEmpty(), "§4 named no teachers at all");
        return List.copyOf(teaches);
    }

    /** @return §5's coordinators */
    public List<CoordinatorRow> coordinators() {
        return map(rows("## 5.", 3), cells ->
                new CoordinatorRow(code(cells[0]), reference(cells[1])));
    }

    /** @return §6 flattened: one row per (student, course) pair */
    public List<EnrollmentRow> enrollments() {
        List<EnrollmentRow> enrolled = new ArrayList<>();
        for (String[] cells : rows("## 6.", 3)) {
            String student = reference(cells[0]);
            for (String course : plain(cells[1]).split(",")) {
                String trimmed = course.trim();
                require(!trimmed.isEmpty(), "§6 gave " + student + " an empty course");
                enrolled.add(new EnrollmentRow(student, trimmed));
            }
        }
        require(!enrolled.isEmpty(), "§6 produced no enrollments");
        return List.copyOf(enrolled);
    }

    /** @return every question in §7.1 through §7.4, in document order */
    public List<QuestionRow> questions() {
        List<QuestionRow> all = new ArrayList<>();
        for (String heading : List.of("### 7.1", "### 7.2", "### 7.3", "### 7.4")) {
            for (String[] cells : rows(heading, 10)) {
                all.add(new QuestionRow(
                        plain(cells[0]), plain(cells[1]), plain(cells[2]), plain(cells[3]),
                        plain(cells[4]), plain(cells[5]), plain(cells[6]), plain(cells[7]),
                        number(cells[8], heading + " correct answer"),
                        plain(cells[9]).equalsIgnoreCase("yes")));
            }
        }
        require(!all.isEmpty(), "§7 produced no questions");
        return List.copyOf(all);
    }

    /** @return §8's six exams */
    public List<ExamRow> exams() {
        return map(rows("## 8.", 6), cells -> new ExamRow(
                number(cells[0], "§8 exam number"), plain(cells[1]), plain(cells[2]),
                plain(cells[3]), reference(cells[4])));
    }

    /**
     * §8's "versions and status" column, which nothing was reading.
     *
     * <p>{@link #exams()} maps the first five cells and stopped, so the column holding
     * {@code v1 **REJECTED**, v2 **APPROVED**} was required to exist and then ignored. That
     * column is the entire point of "6 exams in mixed states": it is the fixture E8's approval
     * flow is demonstrated on, and the loader could have stored any status at all without a
     * single check noticing.
     *
     * <p>The bold here is decoration, unlike §8.1's {@code **v1**}, which is why this reads the
     * status out of it rather than preserving it.
     *
     * @return one row per exam version, in document order
     */
    public List<ExamVersionStatusRow> examVersionStatuses() {
        List<ExamVersionStatusRow> statuses = new ArrayList<>();
        for (String[] cells : rows("## 8.", 6)) {
            int exam = number(cells[0], "§8 exam number");
            Matcher matcher = VERSION_STATUS.matcher(cells[5]);
            int before = statuses.size();
            while (matcher.find()) {
                statuses.add(new ExamVersionStatusRow(exam,
                        Integer.parseInt(matcher.group(1)), matcher.group(2)));
            }
            require(statuses.size() > before, "§8 exam " + exam + " names no version status. "
                    + "The cell format is 'v1 **REJECTED**, v2 **APPROVED**'; if it changed, "
                    + "this parser changes in the same commit.");
        }
        return List.copyOf(statuses);
    }

    /** @return §8.1 flattened: one row per question slot, with its 1-based ordinal */
    public List<CompositionRow> composition() {
        List<CompositionRow> slots = new ArrayList<>();
        for (String[] cells : rows("### 8.1", 4)) {
            int exam = number(cells[0], "§8.1 exam number");
            int version = number(plain(cells[1]).replace("v", ""), "§8.1 version");
            int duration = number(plain(cells[2]).replace("min", ""), "§8.1 duration");

            Matcher matcher = SLOT.matcher(cells[3]);
            int ordinal = 1;
            while (matcher.find()) {
                Integer pinned = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
                slots.add(new CompositionRow(exam, version, duration,
                        matcher.group(1), pinned, Integer.parseInt(matcher.group(3)), ordinal));
                ordinal++;
            }
            require(ordinal > 1, "§8.1 exam " + exam + " v" + version + " listed no questions. "
                    + "The cell format is '11001->20, 11002->20'; if it changed, this parser "
                    + "changes in the same commit.");
        }
        require(!slots.isEmpty(), "§8.1 produced no composition rows");
        return List.copyOf(slots);
    }

    /** @return §8.2's per-exam texts */
    public List<ExamTextRow> examTexts() {
        return map(rows("### 8.2", 3), cells -> new ExamTextRow(
                number(cells[0], "§8.2 exam number"), plain(cells[1]), plain(cells[2])));
    }

    /**
     * §8.2's <em>second</em> table, the rejection reasons.
     *
     * <p>Easy to miss, and it was: §8.2 holds the per-exam texts first and the rejections after a
     * paragraph of prose, so an accessor reading "the first table in §8.2" sees only the texts.
     * The two reasons are stored in {@code exam_versions.rejected_reason} and travel back to the
     * author (T-4.2), which makes them user-visible text that nothing was checking against the
     * document until this existed.
     *
     * <p>Picked by header shape rather than by position, for the same reason §9.1.1's grid is.
     *
     * @return one row per rejected exam version
     */
    public List<RejectionRow> rejectionReasons() {
        List<String[]> table = tables(section("### 8.2"), "### 8.2").stream()
                .filter(candidate -> headerNames(candidate.get(0), "reason"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SEED_CONTENT.md: no table in §8.2 "
                        + "has a 'reason' column. The rejection reasons are stored text and must "
                        + "be checkable against the document."));

        return map(table.subList(1, table.size()), cells -> new RejectionRow(
                number(cells[0], "§8.2 rejection exam number"),
                number(plain(cells[1]).replace("v", ""), "§8.2 rejection version"),
                reference(cells[2]),
                plain(cells[3])));
    }

    /**
     * §9's four executions.
     *
     * <p>The window column is deliberately <b>not</b> parsed. Windows are relative to load time,
     * so an assertion on the resolved instants would have to re-derive them from the anchor,
     * which is re-implementing {@link SeedTimes} in the test and proving only that two copies of
     * one calculation agree. {@code SeedTimesTest} pins the resolution instead, and this pins
     * what the document actually decides: which exam version, which code, which status.
     *
     * @return one row per execution, in document order
     */
    public List<ExecutionRow> executions() {
        // §9 leads with the NOT NULL rules table, added in the 2026-08-20 amendment, so "the
        // first table in §9" is no longer the executions. Picked by header shape instead, which
        // is why that convention exists: a section gaining a table above yours must not silently
        // change which one you read.
        List<String[]> table = tables(section("## 9."), "## 9.").stream()
                .filter(candidate -> headerNames(candidate.get(0), "code"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SEED_CONTENT.md: no table in §9 "
                        + "has a 'code' column, so the executions cannot be told from the rules "
                        + "table above them."));
        require(table.get(0).length == 6, "§9's execution table has " + table.get(0).length
                + " columns where 6 are expected.");

        return map(table.subList(1, table.size()), cells -> {
            String[] examAndVersion = plain(cells[1]).split("/");
            require(examAndVersion.length == 2, "§9's exam cell should read '1 / v2', not '"
                    + cells[1] + "'");
            return new ExecutionRow(
                    number(cells[0], "§9 execution number"),
                    number(examAndVersion[0], "§9 exam number"),
                    number(examAndVersion[1].replace("v", ""), "§9 exam version"),
                    plain(cells[2]),
                    bare(cells[4]));
        });
    }

    /**
     * Per-student grades for one execution.
     *
     * @param execution 1 for §9.1's closed and approved set, 2 for §9.2's awaiting approval
     * @return one row per student
     */
    /**
     * §9.1's manual override, read from the two bullets under its heading rather than a table.
     *
     * <p>Neither string was parsed until 2026-08-27 (B-13), and that is how the document's
     * reason drifted two edits away from the loader's without a test noticing: {@code GradeRow}
     * has no field for either, and the only query that mentions {@code overrideReason} uses it
     * as an {@code is not null} filter and never reads the text. Both reach a student, so they
     * are exactly the kind of string this class exists to hold the loader to.
     *
     * @return the override's reason and teacher comment
     */
    public OverrideRow manualOverride() {
        List<String> lines = section("### 9.1");
        return new OverrideRow(backticked(lines, "- Reason:"),
                backticked(lines, "- Teacher comment to the student"));
    }

    /** @return the backticked value on the first line carrying the prefix */
    private static String backticked(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                int open = line.indexOf('`');
                int close = line.lastIndexOf('`');
                require(open >= 0 && close > open, "§9.1's \"" + prefix
                        + "\" line carries no backticked value, so there is nothing to compare "
                        + "the database against.");
                return line.substring(open + 1, close);
            }
        }
        throw new IllegalStateException("§9.1 has no line starting with \"" + prefix
                + "\". If the bullet was reworded, this parser changes in the same commit.");
    }

    public List<GradeRow> grades(int execution) {
        String heading = execution == 1 ? "### 9.1" : "### 9.2";
        // §9.1 carries a note column and §9.2 does not, which is why the widths differ and why
        // the width check is worth having: reading one with the other's shape would silently
        // take the wrong cell for every field after the third.
        int columns = execution == 1 ? 6 : 5;

        return map(rows(heading, columns), cells -> new GradeRow(
                reference(cells[0]), bare(cells[1]),
                number(plain(cells[2]).replace("min", ""), heading + " solving time"),
                number(bare(cells[3]), heading + " auto score"),
                // §9.2 writes a dash: no grade has been approved, so there is no final score.
                isDash(cells[4]) ? null : number(bare(cells[4]), heading + " final score")));
    }

    /**
     * A selection grid, flattened to one row per cell.
     *
     * <p>The header row names the questions, so a column's meaning comes from the document
     * rather than from a constant here. A dash yields {@code answered = false}.
     *
     * @param execution 1 for §9.1.1, 2 for §9.2.1
     * @return one row per (student, question) cell
     */
    public List<SelectionRow> selections(int execution) {
        String heading = execution == 1 ? "#### 9.1.1" : "#### 9.2.1";
        // Each of these sections holds two tables: the answer key first, then the selection
        // grid. Picked by shape rather than by position, so adding a third table above it
        // cannot silently change which one is read.
        List<String[]> table = tables(section(heading), heading).stream()
                .filter(candidate -> questionColumns(candidate.get(0)).size() >= 3)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SEED_CONTENT.md: no table in "
                        + heading + " has a header naming five-digit questions. That header is what "
                        + "gives each column its meaning, so the grid cannot be read without it."));

        java.util.Map<Integer, String> questions = questionColumns(table.get(0));

        List<SelectionRow> selections = new ArrayList<>();
        for (String[] cells : table.subList(1, table.size())) {
            String student = reference(cells[0]);
            for (java.util.Map.Entry<Integer, String> column : questions.entrySet()) {
                String cell = cells[column.getKey()];
                // An em dash means "never reached". An EMPTY cell does not: here it is a
                // blanked value, and defaulting it to "never reached" would invent a fact
                // about a student's attempt. Elsewhere empty legitimately means absent, which
                // is why isDash is not used for this grid.
                String text = bare(cell);
                require(!text.isEmpty(), heading + " has a blank cell for " + student + " on "
                        + column.getValue() + ". A missing selection must be written as a dash, "
                        + "which means no row in attempt_answers, or as a number.");
                if (text.equals("—") || text.equals("-")) {
                    selections.add(new SelectionRow(student, column.getValue(), false, 0));
                } else {
                    selections.add(new SelectionRow(student, column.getValue(), true,
                            number(text, heading + " selection")));
                }
            }
        }
        require(!selections.isEmpty(), heading + " produced no selections");
        return List.copyOf(selections);
    }

    /**
     * §10's four bots.
     *
     * <p>The {@code active} cell is not a bare yes or no: bot 4 reads
     * {@code **no** — inactive, for the S-31 refusal demo}, because the reason matters as much
     * as the value. Read from the first word, so the explanation can be reworded without
     * silently flipping a flag.
     *
     * @return one row per bot, in document order
     */
    public List<BotRow> bots() {
        return map(rows("## 10.", 4), cells -> {
            String active = bare(cells[3]);
            require(active.startsWith("yes") || active.startsWith("no"),
                    "§10's active cell should start with yes or no, not '" + cells[3] + "'");
            return new BotRow(number(cells[0], "§10 bot number"), code(cells[1]),
                    plain(cells[2]), active.startsWith("yes"));
        });
    }

    /**
     * §10.1's eight sources, which are prose blocks rather than a table.
     *
     * <p>Each is a heading line, {@code **Source 1** · bot 1 · TEXT · `title`}, followed by the
     * body as a block quote. Parsed rather than skipped because those eight paragraphs are the
     * longest hand-transcribed text in the seed, roughly 3400 characters of Hebrew and English,
     * and they are exactly where a silent slip would live.
     *
     * <p><b>The {@code type} returned here is the label, which the document itself contradicts.</b>
     * §10's preamble rules that all eight seed as {@code TEXT}; five of the labels say PDF or
     * DOCX. This reports what the label says, because that is what the document says, and the
     * consumer applies the ruling. Resolving it here would hide the contradiction rather than
     * let a test state it.
     *
     * @return one row per source, in document order
     */
    public List<BotSourceRow> botSources() {
        List<String> lines = section("### 10.1");
        List<BotSourceRow> sources = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            Matcher header = SOURCE_HEADER.matcher(lines.get(i));
            if (!header.matches()) {
                continue;
            }
            String body = null;
            for (int j = i + 1; j < lines.size() && j < i + 4; j++) {
                if (lines.get(j).startsWith("> ")) {
                    body = plain(lines.get(j).substring(2));
                    break;
                }
            }
            require(body != null && !body.isEmpty(), "§10.1 source " + header.group(1)
                    + " has no body. Every source carries real text because raw and "
                    + "extracted_text are both NOT NULL with a LENGTH > 0 check.");

            sources.add(new BotSourceRow(Integer.parseInt(header.group(1)),
                    Integer.parseInt(header.group(2)), header.group(3),
                    plain(header.group(4)), body));
        }

        require(!sources.isEmpty(), "§10.1 produced no sources. The block format is "
                + "'**Source 1** · bot 1 · TEXT · `title`' followed by a quoted body; if it "
                + "changed, this parser changes in the same commit.");
        return List.copyOf(sources);
    }

    /**
     * §10.2's recorded sessions.
     *
     * <p>One row is a session <em>and</em> its single message: the table carries the question,
     * the answer and the provider, which belong to {@code bot_messages}, alongside the student
     * and bot that identify the session. ARCHITECTURE §5 requires the two to be dual-written, so
     * reading them as one row matches how they are produced.
     *
     * @return one row per session, in document order
     */
    public List<BotSessionRow> botSessions() {
        return map(rows("### 10.2", 7), cells -> new BotSessionRow(
                number(cells[0], "§10.2 session number"),
                number(cells[1], "§10.2 bot number"),
                reference(cells[2]),
                number(plain(cells[3]).replaceAll("[^0-9]", ""), "§10.2 days ago"),
                plain(cells[4]), plain(cells[5]), plain(cells[6])));
    }

    /**
     * The author §7's D9 rule gives a question version, derived rather than looked up.
     *
     * <p>§7 states it as a rule instead of a column, deliberately: "if Java ever stops being
     * co-taught, or another course gains a co-teacher, this rule re-resolves on its own, which
     * is why it is a rule and not 43 hand-written values." So this resolves it the same way,
     * from §4's teacher table, and a roster change moves the expectation without anyone editing
     * a list.
     *
     * <ul>
     *   <li><b>v1</b> is the course's first-listed teacher in §4.</li>
     *   <li><b>A second version in a co-taught course</b> is the co-teacher. Java is the only
     *       co-taught course today, so this resolves to exactly one row, {@code 21003} v2.</li>
     *   <li><b>A second version in a singly-taught course</b> stays with the first-listed
     *       teacher, which is why {@code 11005} v2 and {@code 22004} v2 do not move.</li>
     * </ul>
     *
     * @param displayId the five-digit question id, whose first two characters are the course
     * @param versionNo the version within that question
     * @return the username §7's rule attributes it to
     */
    public String expectedQuestionAuthor(String displayId, int versionNo) {
        String course = displayId.substring(0, 2);
        List<String> teachers = courseTeachers().stream()
                .filter(row -> row.course().equals(course))
                .map(TeachesRow::teacher)
                .toList();

        require(!teachers.isEmpty(), "§4 names no teacher for course " + course
                + ", so §7's authorship rule cannot resolve for question " + displayId);

        if (versionNo > 1 && teachers.size() > 1) {
            return teachers.get(1);
        }
        return teachers.get(0);
    }

    /** @return §11's notifications */
    public List<NotificationRow> notifications() {
        // Six columns since the 2026-08-20 amendment added seed_id in front. The width check
        // caught the change rather than reading every field one place to the left, which is
        // what "the markdown shape is part of the contract" is for.
        return map(rows("## 11.", 6), cells -> new NotificationRow(
                plain(cells[0]), number(cells[1], "§11 number"), reference(cells[2]),
                plain(cells[3]), plain(cells[4]),
                plain(cells[5]).equalsIgnoreCase("read")));
    }

    // --------------------------------------------------------------- helpers

    /**
     * Whether stored text is the document's text with the em-dash house rule applied.
     *
     * <p>PRD §4.1 forbids em dashes in user-visible text and permits <b>a comma, a period or a
     * colon</b> in their place. That is three legal renderings, not one, and the loader uses
     * different ones deliberately: a colon reads correctly in a title
     * ({@code Midterm: Algebra}) and a comma in a sentence ({@code Nothing, it is safe}). Both
     * examples were Hebrew until 2026-08-27; the wave-1 translation had removed every Hebrew
     * string from the seed, so they illustrated the rule with data that no longer existed.
     *
     * <p>So this is a predicate rather than a transformation. An earlier version returned "the
     * comma form" and failed every title, which is a test asserting a rule stricter than the one
     * the PRD states. Anchoring on the segments either side of each dash keeps the check exact
     * about everything except the one character the rule leaves to judgement.
     *
     * @param documentText text as the document writes it
     * @param storedText   text as the loader stored it
     * @return whether the two differ only by a permitted replacement at each em dash
     */
    public static boolean followsHouseRule(String documentText, String storedText) {
        String[] parts = documentText.split("\\s*\u2014\\s*", -1);
        if (parts.length == 1) {
            return documentText.equals(storedText);
        }
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                pattern.append("[,.:]\\s?");
            }
            pattern.append(Pattern.quote(parts[i]));
        }
        return storedText.matches(pattern.toString());
    }

    /** @return the lines of a section, from its heading to the next heading of any level */
    private List<String> section(String heading) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(heading)) {
                start = i;
                break;
            }
        }
        require(start >= 0, "section '" + heading + "' is not in the document. Either it was "
                + "renamed, in which case this parser changes in the same commit, or it is gone.");

        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("#")) {
                end = i;
                break;
            }
        }
        return lines.subList(start, end);
    }

    /** @return the data rows of the section's first table, header dropped */
    private List<String[]> rows(String heading, int expectedColumns) {
        List<String[]> table = table(section(heading), heading, expectedColumns);
        return table.subList(1, table.size());
    }

    /**
     * The section's first table, whose header must be exactly the expected width.
     *
     * <p>Asserting the width rather than merely tolerating it is what turns "the columns moved"
     * from a silent misread into a failure, because every accessor below indexes by position.
     */
    private List<String[]> table(List<String> sectionLines, String heading, int expectedColumns) {
        List<String[]> first = tables(sectionLines, heading).get(0);
        require(first.get(0).length == expectedColumns,
                "the first table in '" + heading + "' has " + first.get(0).length
                        + " columns where " + expectedColumns + " are expected. Its cells are "
                        + "read by position, so this is a contract change.");
        return first;
    }

    /**
     * Every table in the section, in order, each including its header row.
     *
     * <p>A section can hold more than one: §9.1.1 states the answer key first and the selection
     * grid second. Taking "the first table" there would silently parse the wrong one, which is
     * exactly the kind of quiet wrongness this class exists to prevent, so callers that expect
     * several tables pick theirs by shape rather than by position.
     *
     * @param sectionLines    the section's lines
     * @param heading         for diagnostics
     * @param expectedColumns the minimum a row must have to belong to a table
     * @return every table found, never empty
     */
    private List<List<String[]>> tables(List<String> sectionLines, String heading) {
        List<List<String[]>> found = new ArrayList<>();
        List<String[]> current = new ArrayList<>();

        for (String line : sectionLines) {
            if (!TABLE_ROW.matcher(line).matches()) {
                if (!current.isEmpty()) {
                    found.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
                continue;
            }
            if (current.isEmpty() && SEPARATOR_ROW.matcher(line).matches()) {
                // A separator before any header is not a table at all.
                continue;
            }
            String[] cells = split(line);
            if (current.size() == 1 && SEPARATOR_ROW.matcher(line).matches()) {
                continue;
            }
            // Every row of a table has its header's width. Admitting a narrower or wider row
            // is how a reformat, an escaped pipe or a struck-out line silently changes which
            // cell each accessor reads, since every accessor indexes by position.
            require(current.isEmpty() || cells.length == current.get(0).length,
                    "a row in '" + heading + "' has " + cells.length + " cells where its header "
                            + "has " + (current.isEmpty() ? cells.length : current.get(0).length)
                            + ". Rows are read by position, so a width change is a contract "
                            + "change and must be made deliberately: " + line.trim());
            current.add(cells);
        }
        if (!current.isEmpty()) {
            found.add(List.copyOf(current));
        }

        require(!found.isEmpty(), "section '" + heading + "' holds no table. A section that "
                + "matches nothing is a build failure, never a silent pass.");
        require(found.get(0).size() > 1, "section '" + heading + "' has a header but no data "
                + "rows. A table that matches zero rows is a build failure, never a silent pass.");
        return found;
    }

    /** Splits a markdown row into cells, dropping the leading and trailing pipe. */
    private static String[] split(String line) {
        String trimmed = line.trim();
        String inner = trimmed.substring(1, trimmed.length() - 1);
        String[] cells = inner.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    /** @return the cell with backticks and paired emphasis removed, whitespace collapsed */
    private static String plain(String cell) {
        String text = cell.replace("`", "");
        text = EMPHASIS.matcher(text).replaceAll("$1");
        return text.replaceAll("\\s+", " ").trim();
    }

    /** @return {@link #plain} with bold markers removed too, for cells where bold is decoration */
    private static String bare(String cell) {
        return plain(cell.replace("**", ""));
    }

    /**
     * Resolves a "3 rina.barak" reference to its username.
     *
     * <p>The document numbers its people and writes both; the number is internal to the document
     * and the username is the stable key. A cell that is only a name is returned as-is.
     */
    private static String reference(String cell) {
        // A trailing parenthetical is a gloss for the reader, not part of the reference:
        // §8.2 writes "3 rina.barak (coordinator of subject 10)". Stripped before matching so
        // the strict pattern below stays strict for every other cell.
        String text = bare(cell).replaceAll("\\s*\\([^)]*\\)\\s*$", "");
        Matcher matcher = REFERENCE.matcher(text);
        return matcher.matches() ? matcher.group(1) : text;
    }

    /** @return whether a header row has a column whose name contains the given word */
    private static boolean headerNames(String[] header, String word) {
        for (String cell : header) {
            if (plain(cell).toLowerCase(Locale.ROOT).contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The five-digit question ids a header names, <b>keyed by their column index</b>.
     *
     * <p>Deliberately a map rather than a compacted list. Collecting the matches into a list and
     * then reading {@code cells[i + 1]} assumes the question columns are the first N and are
     * contiguous. They need not be: if one header cell were written {@code 11005 **v1**}, the way
     * the same document writes it two tables above, that cell would stop matching and every
     * column after it would be read one place to the left, reporting one question's answers
     * under another's name. The count would change too, but the misattribution is the failure
     * that matters and it returns the moment a column is added.
     */
    private static java.util.Map<Integer, String> questionColumns(String[] header) {
        java.util.Map<Integer, String> questions = new java.util.LinkedHashMap<>();
        for (int column = 1; column < header.length; column++) {
            String cell = plain(header[column]);
            if (cell.matches("\\d{5}")) {
                questions.put(column, cell);
            }
        }
        return questions;
    }

    /**
     * The leading code in a cell that pairs one with a name.
     *
     * <p>§4 and §5 write their key and its label together, as {@code `12` Calculus}. The code is
     * the stable half and the Hebrew name is there for a human reading the table.
     */
    private static String code(String cell) {
        String text = plain(cell);
        int space = text.indexOf(' ');
        String head = space < 0 ? text : text.substring(0, space);
        require(!head.isEmpty(), "expected a code at the start of '" + cell + "'");
        return head;
    }

    private static boolean isDash(String cell) {
        String text = bare(cell);
        return text.equals("\u2014") || text.equals("-") || text.isEmpty();
    }

    /**
     * A single whole number, and nothing else.
     *
     * <p>An earlier version stripped everything that was not a digit or a minus, which turns a
     * perfectly reasonable edit into a plausible wrong answer rather than an error: writing a
     * duration as {@code 1h 15 min} instead of {@code 75 min} yielded <b>115</b>, and the
     * document's {@code T−14d} yields 14. Requiring the whole cell to be the number means a
     * format the parser was not taught fails loudly instead.
     */
    private static int number(String cell, String what) {
        String text = bare(cell).trim();
        require(text.matches("-?\\d+"), what + " is not a single whole number: '" + cell
                + "'. A cell holding more than the number, such as '1h 15 min', would otherwise "
                + "be read as a plausible wrong value rather than rejected.");
        return Integer.parseInt(text);
    }

    private static <T> List<T> map(List<String[]> rows, java.util.function.Function<String[], T> to) {
        List<T> mapped = new ArrayList<>(rows.size());
        rows.forEach(cells -> mapped.add(to.apply(cells)));
        return List.copyOf(mapped);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("SEED_CONTENT.md: " + message);
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "SeedDocument[%d lines]", lines.size());
    }
}
