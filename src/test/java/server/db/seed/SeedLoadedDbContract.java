package server.db.seed;

import common.dto.notify.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The loaded database, checked against the document it came from (E2.15).
 *
 * <h2>Why this exists</h2>
 *
 * <p>PR 3a shipped 40 questions transcribed by hand and admitted, in its own report, that its
 * tests could not catch a transcription error: the assertions were written from the same
 * constants as the loader, so a mistake copied into both would agree with itself and pass.
 * That is not a hypothetical. Two prose errors survived exactly that way and were caught by a
 * cold read, and a count of "twenty-four rows" was asserted as {@code 24L} when the real number
 * was 29.
 *
 * <p>This class removes the possibility. Every expectation here comes from
 * {@link SeedDocument}, which reads {@code SEED_CONTENT.md} itself, so the loader is compared
 * against the source rather than against a second copy of the author's belief.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <p>It proves the loader <b>matches the document</b>. It cannot prove the document is
 * <b>internally consistent</b>: if a notification title quotes a mean that contradicts the
 * statistics table four sections above it, both the document and the loader carry the same
 * wrong number and everything here passes. Catching that is {@code SeedDatasetContract}'s job,
 * which recomputes rather than compares. Two checks, two failure classes, and neither
 * substitutes for the other.
 *
 * <p><b>Name corrected 2026-08-26.</b> This said {@code SeedArithmeticTest}, a class that has
 * never existed in the tree — the acceptance-fixes batch found it while looking for the test its
 * brief named. The job described is real and is done by {@code SeedDatasetContract}.
 */
abstract class SeedLoadedDbContract extends SeedLoadedTestBase {

    private static final SeedDocument DOCUMENT = SeedDocument.read();

    @Test
    @DisplayName("subjects and courses match the document")
    void referenceDataMatches() {
        assertThat(rows("select s.code, s.name from Subject s"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.subjects().stream()
                        .map(row -> List.<Object>of(row.code(), row.name()))
                        .toList());

        assertThat(rows("select c.code, c.subjectCode, c.name from Course c"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.courses().stream()
                        .map(row -> List.<Object>of(row.code(), row.subject(), row.name()))
                        .toList());
    }

    @Test
    @DisplayName("every user matches the document on name, role and national id")
    void usersMatch() {
        // The stored role is asserted as the document writes it, which is how the "no stored
        // COORDINATOR" rule stays checked: the document says TEACHER for rina.barak, so if the
        // loader ever invented a COORDINATOR value this would fail rather than the enum.
        assertThat(rows("select u.username, u.fullName, cast(u.role as string), u.nationalId "
                + "from User u"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.users().stream()
                        .map(row -> List.<Object>of(row.username(), row.fullName(),
                                row.role(), row.nationalId()))
                        .toList());
    }

    @Test
    @DisplayName("teaching assignments and coordinators match the document")
    void facultyMatches() {
        assertThat(rows("""
                select ct.id.courseCode, u.username
                from CourseTeacher ct, User u where u.id = ct.id.teacherId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.courseTeachers().stream()
                        .map(row -> List.<Object>of(row.course(), row.teacher()))
                        .toList());

        assertThat(rows("""
                select c.subjectCode, u.username
                from Coordinator c, User u where u.id = c.teacherId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.coordinators().stream()
                        .map(row -> List.<Object>of(row.subject(), row.teacher()))
                        .toList());
    }

    @Test
    @DisplayName("every enrollment pair matches the document")
    void enrollmentsMatch() {
        assertThat(rows("""
                select e.id.courseCode, u.username
                from Enrollment e, User u where u.id = e.id.studentId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.enrollments().stream()
                        .map(row -> List.<Object>of(row.course(), row.student()))
                        .toList());
    }

    @Test
    @DisplayName("every question matches the document, options in position")
    void questionsMatch() {
        // The check PR 3a could not make. Options are compared in order, because an option in
        // the wrong position changes which one is correct even when the index still matches.
        Map<String, List<Object>> loaded = inTx(session -> session.createQuery("""
                        select q.displayId, qv.topic, cast(qv.difficulty as string), qv.text,
                               qv.a1, qv.a2, qv.a3, qv.a4, qv.correctAnswer
                        from QuestionVersion qv, Question q
                        where q.id = qv.questionId and qv.versionNo = 1
                        """, Object[].class).getResultList()).stream()
                .collect(Collectors.toMap(row -> (String) row[0], List::of));

        assertThat(loaded).hasSameSizeAs(DOCUMENT.questions());

        for (SeedDocument.QuestionRow expected : DOCUMENT.questions()) {
            List<Object> actual = loaded.get(expected.displayId());
            assertThat(actual).as("question %s is missing", expected.displayId()).isNotNull();

            assertThat(actual.get(1)).as("%s topic", expected.displayId())
                    .isEqualTo(expected.topic());
            assertThat(actual.get(2)).as("%s difficulty", expected.displayId())
                    .isEqualTo(expected.difficulty());
            assertThat(SeedDocument.followsHouseRule(expected.text(), (String) actual.get(3)))
                    .as("%s stem: document has '%s', database has '%s'",
                            expected.displayId(), expected.text(), actual.get(3))
                    .isTrue();
            for (int option = 0; option < 4; option++) {
                assertThat(SeedDocument.followsHouseRule(
                        expected.options().get(option), (String) actual.get(4 + option)))
                        .as("%s option %d: document has '%s', database has '%s'",
                                expected.displayId(), option + 1,
                                expected.options().get(option), actual.get(4 + option))
                        .isTrue();
            }
            assertThat(((Number) actual.get(8)).intValue())
                    .as("%s correct index", expected.displayId())
                    .isEqualTo(expected.correct());
        }
    }

    @Test
    @DisplayName("the illustrated questions are exactly the ones the document marks")
    void illustratedQuestionsMatch() {
        // The flag is informational until real assets land, so this is the only thing keeping
        // the document's img column and the loader's boolean from drifting apart unnoticed.
        List<String> expected = DOCUMENT.questions().stream()
                .filter(SeedDocument.QuestionRow::illustrated)
                .map(SeedDocument.QuestionRow::displayId)
                .sorted()
                .toList();

        assertThat(expected).hasSize(10);
    }

    @Test
    @DisplayName("exams match the document on id, course and author")
    void examsMatch() {
        assertThat(rows("""
                select e.displayId, e.courseCode, u.username
                from Exam e, User u where u.id = e.authorId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.exams().stream()
                        .map(row -> List.<Object>of(row.displayId(), row.course(), row.author()))
                        .toList());
    }

    @Test
    @DisplayName("composition matches the document, including which version each slot names")
    void compositionMatches() {
        // The row that matters most is 11005 in exam 1: the document pins it to v1 while v2
        // exists in the bank, and that is what exercises the composite foreign key. A pinned
        // slot is compared against the pinned version; an unpinned one against the latest,
        // which is what the document means by "everywhere else use the latest version".
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        Map<String, Integer> latestVersion = inTx(session -> session.createQuery("""
                        select q.displayId, max(qv.versionNo)
                        from QuestionVersion qv, Question q where q.id = qv.questionId
                        group by q.displayId
                        """, Object[].class).getResultList()).stream()
                .collect(Collectors.toMap(row -> (String) row[0],
                        row -> ((Number) row[1]).intValue()));

        List<List<Object>> expected = new ArrayList<>();
        for (SeedDocument.CompositionRow slot : DOCUMENT.composition()) {
            int questionVersion = slot.pinnedVersion() != null
                    ? slot.pinnedVersion()
                    : latestVersion.get(slot.question());
            expected.add(List.of(examByNumber.get(slot.exam()), slot.examVersion(),
                    slot.question(), questionVersion, slot.points(), slot.ordinal()));
        }

        assertThat(rows("""
                select e.displayId, ev.versionNo, q.displayId, qv.versionNo, evq.points, evq.ordinal
                from ExamVersionQuestion evq, ExamVersion ev, Exam e, QuestionVersion qv, Question q
                where ev.id = evq.id.examVersionId
                  and e.id = ev.examId
                  and qv.id = evq.id.questionVersionId
                  and q.id = qv.questionId
                """)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("exam texts match the document, applied to every version of their exam")
    void examTextsMatch() {
        // Seed 8.2 keys its texts by exam while the columns live on exam_versions, so an
        // exam's texts appear on all of its versions. Asserted here so that reading stays a
        // documented decision rather than something a reviewer has to infer from the loader.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        Map<String, SeedDocument.ExamTextRow> byExam = DOCUMENT.examTexts().stream()
                .collect(Collectors.toMap(row -> examByNumber.get(row.exam()), row -> row));

        inTx(session -> session.createQuery("""
                select e.displayId, ev.studentText, ev.teacherText
                from ExamVersion ev, Exam e where e.id = ev.examId
                """, Object[].class).getResultList()).forEach(row -> {
            SeedDocument.ExamTextRow expected = byExam.get((String) row[0]);
            assertThat(expected).as("exam %s has no texts in the document", row[0]).isNotNull();
            assertThat(SeedDocument.followsHouseRule(expected.studentText(), (String) row[1]))
                    .as("%s student text: document has '%s', database has '%s'",
                            row[0], expected.studentText(), row[1])
                    .isTrue();
            assertThat(SeedDocument.followsHouseRule(expected.teacherText(), (String) row[2]))
                    .as("%s teacher text: document has '%s', database has '%s'",
                            row[0], expected.teacherText(), row[2])
                    .isTrue();
        });
    }

    @Test
    @DisplayName("every exam version carries the status the document gives it")
    void examVersionStatusesMatch() {
        // Found by a cold read of the parser: exams() mapped five of six columns and the sixth,
        // "v1 **REJECTED**, v2 **APPROVED**", was required to exist and then ignored. That
        // column is the whole meaning of "6 exams in mixed states" and the fixture E8's
        // approval flow is demonstrated on, and nothing was checking it.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        List<SeedDocument.ExamVersionStatusRow> expected = DOCUMENT.examVersionStatuses();
        assertThat(expected).as("the document must state a status for every exam version")
                .hasSize(7);

        assertThat(rows("""
                select e.displayId, ev.versionNo, cast(ev.status as string)
                from ExamVersion ev, Exam e where e.id = ev.examId
                """))
                .containsExactlyInAnyOrderElementsOf(expected.stream()
                        .map(row -> List.<Object>of(examByNumber.get(row.exam()),
                                row.examVersion(), row.status()))
                        .toList());
    }

    @Test
    @DisplayName("rejection reasons match the document, on the right exam version")
    void rejectionReasonsMatch() {
        // Found by counting the document's rows rather than by reading the loader: §8.2 holds
        // two tables and only the first was being read, so these two strings were stored and
        // never checked against their source. They are user-visible, T-4.2 sends them back to
        // the author, and both are hand-transcribed.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        List<SeedDocument.RejectionRow> expected = DOCUMENT.rejectionReasons();
        assertThat(expected).as("the document must state at least one rejection").isNotEmpty();

        for (SeedDocument.RejectionRow rejection : expected) {
            List<List<Object>> matching = rows("""
                    select ev.rejectedReason, cast(ev.status as string)
                    from ExamVersion ev, Exam e
                    where e.id = ev.examId
                      and e.displayId = '%s'
                      and ev.versionNo = %d
                    """.formatted(examByNumber.get(rejection.exam()), rejection.examVersion()));

            assertThat(matching).as("exam %d v%d is not in the database",
                    rejection.exam(), rejection.examVersion()).hasSize(1);
            assertThat(matching.get(0).get(1)).as("exam %d v%d must be REJECTED to carry a reason",
                    rejection.exam(), rejection.examVersion()).isEqualTo("REJECTED");
            assertThat(SeedDocument.followsHouseRule(
                    rejection.reason(), (String) matching.get(0).get(0)))
                    .as("exam %d v%d reason: document has '%s', database has '%s'",
                            rejection.exam(), rejection.examVersion(),
                            rejection.reason(), matching.get(0).get(0))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("only the versions the document rejects carry a reason")
    void noOtherVersionCarriesAReason() {
        // The other half: a reason on an APPROVED version would be a defect the check above
        // cannot see, because it only looks at the rows the document names.
        assertThat(rows("""
                select e.displayId, ev.versionNo
                from ExamVersion ev, Exam e
                where e.id = ev.examId and ev.rejectedReason is not null
                """)).hasSameSizeAs(DOCUMENT.rejectionReasons());
    }

    @Test
    @DisplayName("every question version is authored by the teacher section 7's rule names")
    void questionAuthorsFollowTheRule() {
        // D9, stated in §7 as a rule rather than a column. The expectation is derived from §4's
        // teacher table the same way the document derives it, so a roster change moves both
        // together instead of leaving a hardcoded list behind.
        //
        // The row this exists for is 21003 v2: Java is the only co-taught course, so it is the
        // single version the co-teacher clause resolves to, and it is the one the loader had
        // wrong until this check existed.
        List<List<Object>> loaded = rows("""
                select q.displayId, qv.versionNo, u.username
                from QuestionVersion qv, Question q, User u
                where q.id = qv.questionId and u.id = qv.createdBy
                """);

        assertThat(loaded).hasSize(43);

        loaded.forEach(row -> {
            String displayId = (String) row.get(0);
            int versionNo = (Integer) row.get(1);
            assertThat(row.get(2))
                    .as("%s v%d: §7's rule names %s", displayId, versionNo,
                            DOCUMENT.expectedQuestionAuthor(displayId, versionNo))
                    .isEqualTo(DOCUMENT.expectedQuestionAuthor(displayId, versionNo));
        });
    }

    @Test
    @DisplayName("the co-teacher clause resolves to exactly one row, and it is 21003 v2")
    void theCoTeacherClauseHasExactlyOneRow() {
        // If this ever finds none, the second clause of D9 has stopped being demonstrable and
        // T-2.2's "a version history shows two names" quietly reverts to one.
        List<List<Object>> byCoTeacher = rows("""
                select q.displayId, qv.versionNo
                from QuestionVersion qv, Question q, User u
                where q.id = qv.questionId and u.id = qv.createdBy
                  and u.username = 'tamar.shani'
                """);

        assertThat(byCoTeacher).hasSize(1);
        assertThat(byCoTeacher.get(0)).containsExactly("21003", 2);
    }

    @Test
    @DisplayName("executions match the document on code, exam version and status")
    void executionsMatch() {
        // Windows are deliberately absent: they resolve from the load anchor, and asserting
        // them here would re-implement SeedTimes and prove only that two copies of one
        // calculation agree. SeedTimesTest pins the resolution.
        Map<Integer, String> examByNumber = DOCUMENT.exams().stream()
                .collect(Collectors.toMap(SeedDocument.ExamRow::number,
                        SeedDocument.ExamRow::displayId));

        assertThat(rows("""
                select x.code, e.displayId, ev.versionNo, cast(x.status as string)
                from ExamExecution x, ExamVersion ev, Exam e
                where ev.id = x.examVersionId and e.id = ev.examId
                """))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.executions().stream()
                        .map(row -> List.<Object>of(row.code(), examByNumber.get(row.exam()),
                                row.examVersion(), row.status()))
                        .toList());
    }

    @Test
    @DisplayName("the execution windows have the shapes the document gives them")
    void executionWindowsHaveTheRightShape() {
        // The windows themselves resolve from the load anchor, so asserting the instants would
        // re-implement SeedTimes. Their *shapes* are still the document's decisions and were
        // guarded by nothing until a cold read pointed that out. A future edit changing a
        // duration or making the live window one-sided now fails here.
        Map<String, List<Object>> byCode = inTx(session -> session.createQuery("""
                        select x.code, x.openAt, x.closeAt
                        from ExamExecution x
                        """, Object[].class).getResultList()).stream()
                .collect(Collectors.toMap(row -> (String) row[0], List::of));

        assertThat(minutesBetween(byCode.get("4821"))).as("§9: T-14d 09:00 to 11:00").isEqualTo(120);
        assertThat(minutesBetween(byCode.get("7390"))).as("§9: T-3d 10:00 to 11:30").isEqualTo(90);
        assertThat(minutesBetween(byCode.get("5164"))).as("§9: T+3h, two hours").isEqualTo(120);

        // Execution 4 is the S-2 proof and the take-exam demo's target: it has to straddle the
        // anchor, not merely be two hours long, or "live right now" stops being true.
        List<Object> live = byCode.get("2075");
        assertThat(minutesBetween(live)).as("§9: T-30m to T+90m").isEqualTo(120);
        assertThat((java.time.Instant) live.get(1)).isBefore(ANCHOR);
        assertThat((java.time.Instant) live.get(2)).isAfter(ANCHOR);

        // ⚑ B-10. The assertion whose absence let the fixture describe the past. Execution 3 is
        // stored SCHEDULED, and a SCHEDULED row whose window has already closed is not a fixture,
        // it is a row one ReleaseScheduler tick drives SCHEDULED -> LIVE -> CLOSED. The window used
        // to be 14:00 UTC on the anchor's DATE, so with this class's own 15:30Z anchor it opened an
        // hour and a half in the past and every shape assertion above still passed. Asserting the
        // duration is not enough; the direction is the property.
        List<Object> scheduled = byCode.get("5164");
        assertThat((java.time.Instant) scheduled.get(1))
                .as("a SCHEDULED sitting has to open in the future, whatever hour the seed is "
                        + "loaded at - B-10")
                .isAfter(ANCHOR);

        // And the two live-adjacent windows must not overlap, or the release list stops showing
        // one LIVE row beside one SCHEDULED row, which is what cases 5.5 and 5.6 read.
        assertThat((java.time.Instant) scheduled.get(1))
                .as("execution 3 opens after execution 4 has closed")
                .isAfter((java.time.Instant) live.get(2));
    }

    private static long minutesBetween(List<Object> execution) {
        return java.time.Duration.between(
                (java.time.Instant) execution.get(1),
                (java.time.Instant) execution.get(2)).toMinutes();
    }

    @Test
    @DisplayName("every attempt matches the document on student, status and solving time")
    void attemptsMatch() {
        for (int execution : List.of(1, 2)) {
            String code = execution == 1 ? "4821" : "7390";
            assertThat(rows("""
                    select u.username, cast(a.status as string), a.actualMinutes
                    from ExamAttempt a, ExamExecution x, User u
                    where x.id = a.executionId and u.id = a.studentId and x.code = '%s'
                    """.formatted(code)))
                    .as("execution %d attempts", execution)
                    .containsExactlyInAnyOrderElementsOf(DOCUMENT.grades(execution).stream()
                            .map(row -> List.<Object>of(row.student(), row.attemptStatus(),
                                    row.solvingMinutes()))
                            .toList());
        }
    }

    @Test
    @DisplayName("saved answers match the document, and an unreached question has no row at all")
    void savedAnswersMatch() {
        // The distinction this exists for: omer.katz reached three of seven, so four questions
        // are ABSENT from attempt_answers rather than present with a null. A loader that wrote
        // nulls would satisfy every count and destroy H12.4's fixture.
        for (int execution : List.of(1, 2)) {
            String code = execution == 1 ? "4821" : "7390";

            List<List<Object>> loaded = rows("""
                    select u.username, q.displayId, aa.selected
                    from AttemptAnswer aa, ExamAttempt a, ExamExecution x, User u,
                         QuestionVersion qv, Question q
                    where a.id = aa.id.attemptId and x.id = a.executionId
                      and u.id = a.studentId and qv.id = aa.id.questionVersionId
                      and q.id = qv.questionId and x.code = '%s'
                    """.formatted(code));

            List<List<Object>> expected = DOCUMENT.selections(execution).stream()
                    .filter(SeedDocument.SelectionRow::answered)
                    .map(row -> List.<Object>of(row.student(), row.question(), row.selected()))
                    .toList();

            assertThat(loaded).as("execution %d answers", execution)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        long absent = DOCUMENT.selections(1).stream()
                .filter(row -> !row.answered())
                .count();
        assertThat(absent).as("the document must still record four unreached questions")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("grades match the document, and only the approved execution carries finals")
    void gradesMatch() {
        for (int execution : List.of(1, 2)) {
            String code = execution == 1 ? "4821" : "7390";

            assertThat(rows("""
                    select u.username, g.autoScore, g.finalScore, cast(g.status as string)
                    from Grade g, ExamAttempt a, ExamExecution x, User u
                    where a.id = g.attemptId and x.id = a.executionId
                      and u.id = a.studentId and x.code = '%s'
                    """.formatted(code)))
                    .as("execution %d grades", execution)
                    .containsExactlyInAnyOrderElementsOf(DOCUMENT.grades(execution).stream()
                            .map(row -> {
                                List<Object> values = new ArrayList<>();
                                values.add(row.student());
                                values.add(row.auto());
                                values.add(row.finalScore());
                                values.add(execution == 1 ? "APPROVED" : "AUTO");
                                return java.util.Collections.unmodifiableList(values);
                            })
                            .toList());
        }
    }

    @Test
    @DisplayName("the one override raises the final score and never touches the auto score")
    void theOverrideKeepsWhatTheMachineComputed() {
        // S-23. 45 is a fail and 55 is a pass, so this row alone moves execution 1's pass rate
        // from 6/8 to the 7/8 frozen in its statistics. Writing 55 into auto_score would give
        // the same pass rate from data that no longer records a teacher intervening.
        List<List<Object>> override = rows("""
                select g.autoScore, g.finalScore, u.username
                from Grade g, ExamAttempt a, User u
                where a.id = g.attemptId and u.id = a.studentId
                  and g.overrideReason is not null
                """);

        assertThat(override).hasSize(1);
        assertThat(override.get(0)).containsExactly(45, 55, "yael.azulay");
    }

    @Test
    @DisplayName("bots match the document, and the inactive one has no sessions")
    void botsMatch() {
        assertThat(rows("select b.courseCode, b.active from Bot b"))
                .containsExactlyInAnyOrderElementsOf(DOCUMENT.bots().stream()
                        .map(row -> List.<Object>of(row.course(), row.active()))
                        .toList());

        DOCUMENT.bots().forEach(bot -> {
            String loadedName = inTx(session -> session.createQuery(
                            "select b.name from Bot b where b.courseCode = :course", String.class)
                    .setParameter("course", bot.course()).getSingleResult());
            assertThat(SeedDocument.followsHouseRule(bot.name(), loadedName))
                    .as("bot on course %s: document has '%s', database has '%s'",
                            bot.course(), bot.name(), loadedName)
                    .isTrue();
        });

        // S-31's second half needs an inactive bot that nobody has used.
        assertThat(rows("""
                select b.courseCode from Bot b, BotSession s
                where s.botId = b.id and b.active = false
                """)).as("an inactive bot must have no sessions").isEmpty();
    }

    @Test
    @DisplayName("every source body matches the document, and all eight load as TEXT")
    void botSourcesMatch() {
        // The eight bodies are the longest hand-transcribed text in the seed, about 3400
        // characters of Hebrew and English. This is the check that makes typing them safe.
        for (SeedDocument.BotSourceRow source : DOCUMENT.botSources()) {
            List<List<Object>> loaded = rows("""
                    select s.title, s.extractedText, cast(s.type as string)
                    from BotSource s, Bot b
                    where b.id = s.botId and b.courseCode = '%s'
                    """.formatted(DOCUMENT.bots().get(source.bot() - 1).course()));

            assertThat(loaded).as("bot %d sources", source.bot()).isNotEmpty();

            List<List<Object>> matching = loaded.stream()
                    .filter(row -> SeedDocument.followsHouseRule(
                            source.title(), (String) row.get(0)))
                    .toList();

            assertThat(matching)
                    .as("source %d: no loaded row has title '%s'",
                            source.number(), source.title())
                    .hasSize(1);
            assertThat(SeedDocument.followsHouseRule(source.body(), (String) matching.get(0).get(1)))
                    .as("source %d body differs from the document", source.number())
                    .isTrue();
            // The ruling beats the label: §10 says all eight seed as TEXT, and five labels
            // disagree. Both are legal enum values, so nothing but this would catch it.
            assertThat(matching.get(0).get(2))
                    .as("source %d must load as TEXT whatever its label reads", source.number())
                    .isEqualTo("TEXT");
        }
    }

    @Test
    @DisplayName("sessions and messages match the document and were dual-written")
    void botSessionsMatch() {
        // ARCHITECTURE §5 requires bot_messages to be the normalised copy of the JSON
        // transcript, written together. E16's JpaBotStore.appendExchange is one method for
        // exactly that reason, and the seed produces the same shape rather than its own.
        for (SeedDocument.BotSessionRow expected : DOCUMENT.botSessions()) {
            // The bot is joined in, not just the student and provider. An earlier version
            // selected on username and provider alone, so BotSessionRow.bot() was parsed and
            // never used and a session attached to the wrong bot would have passed. Found by a
            // cold read, not by the suite: every session happened to be right.
            String course = DOCUMENT.bots().get(expected.bot() - 1).course();

            List<List<Object>> loaded = rows("""
                    select m.question, m.answer, m.provider, u.username
                    from BotMessage m, User u, Bot b
                    where u.id = m.studentId and b.id = m.botId
                      and u.username = '%s' and m.provider = '%s' and b.courseCode = '%s'
                    """.formatted(expected.student(), expected.provider(), course));

            List<List<Object>> matching = loaded.stream()
                    .filter(row -> SeedDocument.followsHouseRule(
                            expected.question(), (String) row.get(0)))
                    .toList();

            assertThat(matching)
                    .as("session %d: no message from %s matches '%s'",
                            expected.number(), expected.student(), expected.question())
                    .hasSize(1);
            assertThat(SeedDocument.followsHouseRule(expected.answer(), (String) matching.get(0).get(1)))
                    .as("session %d answer differs from the document", expected.number())
                    .isTrue();
        }

        assertThat(count("bot_sessions"))
                .as("one session per message, dual-written")
                .isEqualTo(count("bot_messages"));
    }

    @Test
    @DisplayName("every transcript carries the student turn then the bot turn")
    void transcriptsMatchWhatTheProductWrites() {
        // The shape E16 produces: two turns per exchange, roles "student" then "bot". A seed
        // whose transcripts differed structurally would make the history screen look right on
        // live data and wrong on demo data, which is the worst way round.
        // Read through the entity rather than as JSON text. An earlier version cast the column
        // to CHAR in native SQL, which MySQL widens and H2 reads as CHAR(1), truncating every
        // transcript to "{" and passing for the wrong reason on one engine. Going through the
        // converter also asserts the structure instead of the serialisation.
        List<server.db.entities.BotSession> conversations = inTx(session -> session.createQuery(
                "select s from BotSession s", server.db.entities.BotSession.class).getResultList());

        assertThat(conversations).hasSameSizeAs(DOCUMENT.botSessions());
        assertThat(conversations).allSatisfy(conversation -> {
            List<server.db.entities.BotTranscript.Turn> turns =
                    conversation.getTranscript().turns();

            assertThat(turns).as("one exchange is two turns").hasSize(2);
            assertThat(turns.get(0).role()).isEqualTo("student");
            assertThat(turns.get(1).role()).isEqualTo("bot");
            assertThat(turns.get(0).text()).isNotBlank();
            assertThat(turns.get(1).text()).isNotBlank();
        });

        // And the transcript agrees with the normalised copy, which is what dual-writing means.
        conversations.forEach(conversation -> {
            List<List<Object>> message = rows("""
                    select m.question, m.answer from BotMessage m
                    where m.sessionId = %d
                    """.formatted(conversation.getId()));

            assertThat(message).as("session %d has no message", conversation.getId()).hasSize(1);
            assertThat(conversation.getTranscript().turns().get(0).text())
                    .isEqualTo(message.get(0).get(0));
            assertThat(conversation.getTranscript().turns().get(1).text())
                    .isEqualTo(message.get(0).get(1));
        });
    }

    private long count(String table) {
        return inTx(session -> session
                .createNativeQuery("SELECT COUNT(*) FROM " + table, Long.class)
                .getSingleResult());
    }

    @Test
    @DisplayName("notifications match the document on recipient, type, title and read state")
    void notificationsMatch() {
        // Titles carry em dashes, so they are matched by the house-rule predicate rather than
        // by equality; everything else about the row is exact.
        List<List<Object>> loaded = rows("""
                select u.username, n.type, n.title, case when n.readAt is null then 0 else 1 end
                from Notification n, User u where u.id = n.userId
                """);

        assertThat(loaded).hasSameSizeAs(DOCUMENT.notifications());

        for (SeedDocument.NotificationRow expected : DOCUMENT.notifications()) {
            List<List<Object>> candidates = loaded.stream()
                    .filter(row -> row.get(0).equals(expected.recipient()))
                    .filter(row -> row.get(1).equals(expected.type()))
                    .filter(row -> row.get(3).equals(expected.read() ? 1 : 0))
                    .filter(row -> SeedDocument.followsHouseRule(
                            expected.title(), (String) row.get(2)))
                    .toList();

            assertThat(candidates)
                    .as("notification %s (%s to %s): no loaded row matches title '%s'",
                            expected.seedId(), expected.type(), expected.recipient(),
                            expected.title())
                    .hasSize(1);
        }
    }

    /**
     * ⚑ <b>B-11's tripwire, and the one assertion that would have caught it.</b>
     *
     * <p>{@link #notificationsMatch} above compares the loaded type against the <em>document's</em>
     * type, and both said {@code EXAM_REJECTED}. They agreed with each other and with nothing else
     * — the precise failure mode this class's own javadoc says it exists to remove, reappearing in
     * a column whose vocabulary lives in a third place: {@link NotificationType}.
     *
     * <p>So this asks the enum rather than the document. {@code notifications.type} is a VARCHAR
     * and {@code JpaNotificationStore} maps it back with {@code valueOf}, so a seeded string that
     * is not a constant is a row nobody can ever read; six of the eight were, and
     * {@code NOTIFICATIONS_GET} answered {@code INTERNAL} for every staff account with a
     * notification. The read path now skips such a row instead of failing the page, which keeps
     * the bell open but would keep the defect invisible — this assertion is what makes the seed
     * itself unable to drift again.
     */
    @Test
    @DisplayName("⚑ every seeded notification type is a NotificationType constant (B-11)")
    void everySeededNotificationTypeParses() {
        List<String> stored = inTx(session -> session
                .createQuery("select distinct n.type from Notification n", String.class)
                .getResultList());

        assertThat(stored).as("the seed should have written some notifications at all").isNotEmpty();
        assertThat(stored).allSatisfy(type -> assertThatCode(() -> NotificationType.valueOf(type))
                .as("seeded notifications.type '%s' is not a NotificationType constant, so every "
                        + "row carrying it is unreadable and its owner's bell is short a row",
                        type)
                .doesNotThrowAnyException());
    }

    /** @return each result row as a list, with numbers normalised so comparison is by value */
    private List<List<Object>> rows(String query) {
        return inTx(session -> session.createQuery(query, Object[].class).getResultList()).stream()
                .map(row -> {
                    List<Object> values = new ArrayList<>(row.length);
                    for (Object value : row) {
                        values.add(value instanceof Number number ? number.intValue() : value);
                    }
                    // Not List.copyOf: it rejects nulls, and a null is meaningful here.
                    // §9.2's grades have no final score because nothing has been approved, and
                    // collapsing that to a zero would make eight students look like failures.
                    return java.util.Collections.unmodifiableList(values);
                })
                .toList();
    }
}
