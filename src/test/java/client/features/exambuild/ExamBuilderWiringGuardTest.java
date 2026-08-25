package client.features.exambuild;

import client.core.Route;
import client.core.SessionRoutes;
import common.dto.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exam builder is reachable, or this fails (E7.11 ⚑).
 *
 * <p><b>Expected to fail until the assembly lands, and that is the point.</b> Same shape and same
 * reason as {@link ExamListWiringGuardTest}: {@link SessionRoutes} is outside Member A's scope, so
 * everything in this package can be complete, green and unreachable at once. That was #25 on the
 * server and it is available on the client every time a feature adds a screen.
 *
 * <h2>This one is worse than the list's was, and the difference is worth naming</h2>
 *
 * <p>When E7.10 landed, route id {@code exams} already existed and was already offered: only the
 * <em>mapping</em> was wrong. {@code exams.build} does not exist at all. Until the lead declares
 * it in {@code client.core.Routes}, adds it to the authoring roles and maps it to
 * {@link ExamBuilderView}, the Edit and View buttons on the exam list navigate to a route
 * {@code Navigator} will refuse - and {@code Navigator.navigate} <b>throws</b> on an unregistered
 * id rather than doing nothing. So the failure mode this guard prevents is not a dead button, it
 * is an exception in front of a teacher.
 *
 * <h2>Why it reads {@link ExamBuildRoutes} and not {@code client.core.Routes}</h2>
 *
 * <p>Naming a constant that does not exist yet would make this a <b>compile</b> error, taking the
 * whole build down and telling a reader nothing about what is missing. {@link ExamBuildRoutes} is
 * the feature's own copy of the spelling and the one every navigation in the package uses, so
 * pinning it proves the screen is reachable rather than that somebody typed one string twice.
 * {@code BankScreenWiringGuardTest} gave this reasoning first.
 *
 * <h2>The mutations it has to reject</h2>
 *
 * <ol>
 *   <li>the id is declared and mapped to nobody, which is #25;</li>
 *   <li>offered to one authoring role and not the other;</li>
 *   <li>offered to a student, whose every builder verb is refused server-side (F1.2);</li>
 *   <li>offered to the principal, who authors nothing and would get a Save that cannot land;</li>
 *   <li>declared and offered but mapped to the wrong screen, which every role assertion above
 *       would still pass.</li>
 * </ol>
 */
class ExamBuilderWiringGuardTest {

    /** The id the assembly must declare, read from the feature's own constant. */
    private static final String BUILDER_ROUTE_ID = ExamBuildRoutes.BUILDER;

    /**
     * The same id as a literal, so the end-state case cannot follow a mistake.
     *
     * <p>If {@link ExamBuildRoutes#BUILDER} is ever pointed somewhere else, the mapping case must
     * fail rather than move with it. Pinning an end state against a constant that would move
     * along with the error proves nothing, which is the note {@code ExamListWiringGuardTest}
     * carries for the same reason.
     */
    private static final String BUILDER_ID_LITERAL = "exams.build";

    /** Everyone this screen is for: the two roles that may author an exam (contract §2). */
    private static final List<Role> MAY_AUTHOR = List.of(Role.TEACHER, Role.COORDINATOR);

    private static boolean offers(Role role, String routeId) {
        return SessionRoutes.routesFor(role).stream()
                .map(Route::id)
                .anyMatch(id -> Objects.equals(id, routeId));
    }

    @Test
    @DisplayName("the feature's constant and the end-state spelling have not drifted apart")
    void theSpellingIsOne() {
        assertThat(BUILDER_ROUTE_ID).isEqualTo(BUILDER_ID_LITERAL);
    }

    @Test
    @DisplayName("⚑ the builder is offered to somebody, which is what #25 was not")
    void theBuilderIsOfferedAtAll() {
        boolean anyone = MAY_AUTHOR.stream().anyMatch(role -> offers(role, BUILDER_ROUTE_ID));

        assertThat(anyone)
                .as("No role can navigate to route id '%s'. The exam list's Edit and View buttons "
                        + "navigate there, and Navigator.navigate THROWS on an unregistered id, "
                        + "so until SessionRoutes declares it and maps it to ExamBuilderView "
                        + "those buttons are an exception rather than a dead end.",
                        BUILDER_ROUTE_ID)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR"})
    @DisplayName("both roles that may author an exam are offered the builder")
    void everyAuthoringRoleIsOfferedTheBuilder(Role role) {
        assertThat(offers(role, BUILDER_ROUTE_ID))
                .as("%s may author an exam under EXAM_BUILDER_WIRE_CONTRACT §2, and every verb "
                        + "the builder sends is scoped to the exams she wrote. Offering it to one "
                        + "of the two and not the other is how a coordinator finds an Edit button "
                        + "that throws.", role)
                .isTrue();
    }

    @Test
    @DisplayName("a student is never offered the builder")
    void studentsAreNotOfferedTheBuilder() {
        assertThat(SessionRoutes.routesFor(Role.STUDENT))
                .as("guard against the guard: an empty route list would make the assertion below "
                        + "pass by vacuity")
                .isNotEmpty();
        assertThat(offers(Role.STUDENT, BUILDER_ROUTE_ID)).isFalse();
    }

    @Test
    @DisplayName("the principal is never offered the builder ⚑")
    void thePrincipalIsNotOfferedTheBuilder() {
        assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                .as("guard against the guard: she does have routes")
                .isNotEmpty();
        assertThat(offers(Role.PRINCIPAL, BUILDER_ROUTE_ID))
                .as("F9.3 gives her zero mutating verbs, ever. EXAM_CREATE and EXAM_VERSION_SAVE "
                        + "are both writes, so a builder offered to her is a form whose every "
                        + "Save can only be refused. Her read of the school's exams is E15.2's "
                        + "Data screen.")
                .isFalse();
    }

    /**
     * The end state: the id resolves to this feature's builder and not to something else.
     *
     * <p>Separate from the role cases because "the id is offered" will be true the moment the
     * lead adds one line, and it would stay true if the mapping pointed at any other screen. What
     * this pins is <b>which class answers</b>.
     */
    @Test
    @DisplayName("⚑ route id 'exams.build' resolves to ExamBuilderView")
    void theRouteServesTheBuilder() {
        assertThat(buildsTheBuilder())
                .as("Route id '%s' must map to ExamBuilderView in SessionRoutes.builderFor. "
                        + "Until it does, every Edit button on the exam list throws.",
                        BUILDER_ID_LITERAL)
                .isTrue();
    }

    /**
     * Reads the mapping out of {@link SessionRoutes} rather than building the screen.
     *
     * <p>{@code ScreenFactory.get} would answer directly and is the honest way to ask, but it
     * <em>builds</em>, and {@link ExamBuilderView} creates a {@code TabPane}, two
     * {@code FormField}s and a dozen controls in field initialisers, so calling it needs a booted
     * toolkit. That would turn a wiring guard into an FX test for a property that is a line of
     * source. Same limitation the bank's guard states: it proves the mapping is <em>written</em>,
     * not that it runs. {@code ExamBuilderInteractionTest} drives the built screen.
     *
     * <p>Comments are stripped first, so commenting the branch out reads as deleting it.
     */
    private static boolean buildsTheBuilder() {
        String dense = readSessionRoutes()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\\s+", "");

        // Written against the route CONSTANT the lead will add rather than a guessed name for it,
        // so this passes however he spells the field as long as the id and the screen line up.
        return dense.matches(".*Routes\\.[A-Z_]+\\.id\\(\\)\\.equals\\(route\\.id\\(\\)\\)"
                + "\\)\\{returnExamBuilderView::new;.*");
    }

    private static String readSessionRoutes() {
        Path source = Path.of("src", "main", "java", "client", "core", "SessionRoutes.java");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + source.toAbsolutePath()
                    + "; this guard reads the assembly's own source, so it runs from the "
                    + "repository root", e);
        }
    }
}
