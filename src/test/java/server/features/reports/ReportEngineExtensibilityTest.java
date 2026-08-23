package server.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportResult;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.projections.ExecutionReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-37 proved rather than claimed: a fourth report is a strategy class and a registration line
 * (E15.3 ⚑ — F9.4).
 *
 * <p>F9.4's requirement is a claim about future work — "a new report type is a new strategy class
 * plus a menu entry, nothing else" — and claims about future work are the easiest kind to make
 * and the easiest kind to be wrong about. This suite makes it a property of the build in two
 * independent ways, because either one alone could be satisfied by a design that fails the other.
 *
 * <ol>
 *   <li><b>Behaviourally.</b> {@link Fourth} below is a complete dimension strategy that exists
 *       only inside this test file. It is handed to a <em>real</em> {@link ReportEngine} beside
 *       the three shipped ones, and it produces a real report: subjects, rows, frozen statistics
 *       and a pooled summary, all through code that was written before it existed. No wire type,
 *       no handler, no screen and no engine line was touched to make that work.</li>
 *   <li><b>Structurally.</b> The engine's own source is read and asserted to contain none of the
 *       three dimension names and none of the three strategy class names. A behavioural test can
 *       pass over an engine that special-cases the shipped dimensions and falls through to a
 *       generic path for anything else; this is what rules that out.</li>
 * </ol>
 *
 * <p><b>What a real fourth dimension would touch</b>, stated here because this is where a future
 * author will look: one constant in {@code ReportDimension}, one strategy class, one line in
 * {@link ReportStrategies}, and — if it needs a population the repositories cannot already
 * produce — its three methods on {@link ReportData} with their implementations in
 * {@code JpaReportStore} and one query on {@code ExecutionRepository}. The engine, every DTO,
 * both handlers, the summary arithmetic and the whole screen are untouched. {@link Fourth} is
 * the demonstration: it reuses the seam it was handed and needed no change to anything.
 */
class ReportEngineExtensibilityTest {

    private static final Instant WHEN = Instant.parse("2026-08-07T06:00:00Z");

    /** SEED_CONTENT section 9.1's frozen record. */
    private static final ExecutionStats SEEDED = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    // ===================== Behavioural ===================================

    @Nested
    @DisplayName("a dimension that exists only in this test file")
    class ServedWithoutTouchingAnything {

        private final InMemoryReportStore store = seeded();
        private final ReportEngine engine = engineWithAFourthDimension(store);

        @Test
        @DisplayName("⚑ the engine serves a strategy it has never heard of, subjects and all")
        void servesTheFourth() {
            ReportSubjects subjects = engine.subjects(Fourth.DIMENSION).orElseThrow();

            assertThat(subjects.dimension()).isEqualTo(Fourth.DIMENSION);
            assertThat(subjects.subjects()).extracting(ReportSubject::id)
                    .containsExactly(Fourth.EVERYTHING);
            assertThat(subjects.defaultSubject().label()).isEqualTo("The whole school");
        }

        @Test
        @DisplayName("⚑ and it produces a real report: rows, frozen figures and a pooled summary")
        void reportsThroughTheFourth() {
            ReportResult result = engine.report(Fourth.DIMENSION, Fourth.EVERYTHING).orElseThrow();

            assertThat(result.dimension()).isEqualTo(Fourth.DIMENSION);
            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0).statistics().standardDeviation())
                    .as("the frozen figures reach the wire through the same mapping as always")
                    .isEqualTo(17.5);
            assertThat(result.summary().executions()).isEqualTo(2);
            assertThat(result.summary().scored()).isEqualTo(16);
            assertThat(result.summary().mean())
                    .as("the summary arithmetic is the engine's, not the strategy's")
                    .isEqualTo(72.5);
        }

        @Test
        @DisplayName("the shipped dimensions still work beside it, unchanged")
        void theOthersAreUnaffected() {
            assertThat(engine.served()).contains(ReportDimension.values());
            assertThat(engine.report(ReportDimension.BY_TEACHER, "2").orElseThrow().rows())
                    .as("ByTeacherStrategy was handed to the same engine and did not notice")
                    .hasSize(2);
            assertThat(engine.report(ReportDimension.BY_STUDENT, "11").orElseThrow().rows())
                    .hasSize(2);
        }

        @Test
        @DisplayName("an unknown subject on the new dimension refuses like any other")
        void unknownSubjectOnTheFourth() {
            assertThat(engine.report(Fourth.DIMENSION, "not-everything")).isEmpty();
        }
    }

    // ===================== Structural ====================================

    @Nested
    @DisplayName("the engine names no dimension")
    class NoDimensionInTheEngine {

        private static final Path ENGINE =
                Path.of("src", "main", "java", "server", "features", "reports",
                        "ReportEngine.java");

        @Test
        @DisplayName("⚑ ReportEngine.java mentions none of the three dimension constants")
        void engineSourceIsDimensionFree() throws IOException {
            String source = Files.readString(ENGINE, StandardCharsets.UTF_8);

            for (ReportDimension dimension : ReportDimension.values()) {
                assertThat(source)
                        .as("the engine must not know %s exists: a switch here is what would "
                                + "make a fourth report a change to this file", dimension)
                        .doesNotContain(dimension.name());
            }
        }

        @Test
        @DisplayName("⚑ and none of the three strategy classes either")
        void engineSourceNamesNoStrategy() throws IOException {
            String source = Files.readString(ENGINE, StandardCharsets.UTF_8);

            assertThat(source).doesNotContain("ByTeacherStrategy");
            assertThat(source).doesNotContain("ByCourseStrategy");
            assertThat(source).doesNotContain("ByStudentStrategy");
            assertThat(source)
                    .as("nor the registry: the engine takes its list, it does not fetch it")
                    .doesNotContain("ReportStrategies.all()");
        }

        @Test
        @DisplayName("the registration list is one list, and it is the one the server uses")
        void registrationIsOneList() throws IOException {
            String assembly = Files.readString(
                    Path.of("src", "main", "java", "server", "core", "HSTSServer.java"),
                    StandardCharsets.UTF_8);

            assertThat(assembly)
                    .as("the server registers the list rather than the strategies, so a fourth "
                            + "one does not touch the assembly either")
                    .contains("ReportStrategies.all()");
            assertThat(assembly).doesNotContain("ByTeacherStrategy");
            assertThat(assembly).doesNotContain("ByCourseStrategy");
            assertThat(assembly).doesNotContain("ByStudentStrategy");
        }
    }

    // ===================== The fourth dimension ==========================

    /**
     * A whole-school comparison, implemented in a test file and nowhere else.
     *
     * <p>It reuses {@link ReportDimension#BY_COURSE} as its key only because the enum is a wire
     * type and a genuinely new constant would have to be added to it — which is precisely the
     * "one constant" a real fourth dimension costs, and is not something a test should do to a
     * shipped enum. Everything else about it is new: its own subject, its own population, and a
     * row set neither of the shipped strategies produces.
     */
    private static final class Fourth implements DimensionStrategy {

        private static final ReportDimension DIMENSION = ReportDimension.BY_COURSE;
        private static final String EVERYTHING = "school";

        @Override
        public ReportDimension dimension() {
            return DIMENSION;
        }

        @Override
        public List<ReportSubject> subjects(ReportData data) {
            return List.of(new ReportSubject(EVERYTHING, "The whole school", "every course",
                    everySitting(data).size()));
        }

        @Override
        public Optional<ReportSubject> subject(ReportData data, String subjectId) {
            return EVERYTHING.equals(subjectId)
                    ? Optional.of(subjects(data).get(0)) : Optional.empty();
        }

        @Override
        public List<ExecutionReport> executionsOf(ReportData data, String subjectId) {
            return EVERYTHING.equals(subjectId) ? everySitting(data) : List.of();
        }

        /** Every course's sittings, merged: a population no shipped strategy produces. */
        private static List<ExecutionReport> everySitting(ReportData data) {
            List<ExecutionReport> all = new ArrayList<>();
            data.courses().forEach(course ->
                    all.addAll(data.executionsByCourse(course.code())));
            return all;
        }
    }

    // ===================== Fixture =======================================

    private static InMemoryReportStore seeded() {
        return new InMemoryReportStore()
                .teacher(2, "דנה כהן", "dana.cohen")
                .student(11, "מאיה לוי", "maya.levi")
                .course("11", "אלגברה")
                .sitting(1, "4821", WHEN, "מבחן אמצע: אלגברה", "11", 2,
                        ExecutionStatus.CLOSED, SEEDED)
                .sat(1, 11)
                .participants(1, 8)
                .sitting(2, "4822", WHEN.plusSeconds(86_400), "מבחן אמצע: אלגברה", "11", 2,
                        ExecutionStatus.CLOSED, SEEDED)
                .sat(2, 11)
                .participants(2, 8);
    }

    /**
     * The registration a fourth dimension really is: {@code all()} plus one line.
     *
     * <p>{@link Fourth} claims {@code BY_COURSE}, so the shipped strategy for it is left out
     * here rather than duplicated - which is also what the engine's duplicate check would
     * insist on. Everything else in {@link ReportStrategies#all()} is passed through untouched.
     */
    private static ReportEngine engineWithAFourthDimension(InMemoryReportStore store) {
        List<DimensionStrategy> registered = new ArrayList<>();
        for (DimensionStrategy shipped : ReportStrategies.all()) {
            if (shipped.dimension() != Fourth.DIMENSION) {
                registered.add(shipped);
            }
        }
        registered.add(new Fourth());
        return new ReportEngine(store, registered);
    }
}
