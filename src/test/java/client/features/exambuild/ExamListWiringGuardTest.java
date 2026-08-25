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
 * The exam list is reachable and has replaced the screen it retires, or this fails (E7.10 ⚑).
 *
 * <p><b>This test is expected to fail until the assembly lands, and that is the point.</b> The
 * screens in this package are registered by {@link SessionRoutes}, which is outside Member A's
 * scope, so the code in this feature can be complete, green and unreachable at the same time.
 * That is #25 exactly, on the client side. A feature PR that goes green while its screen is on
 * no rail is the failure; a feature PR that refuses to go green until somebody wires it is the
 * guard working. Same shape as {@code BankScreenWiringGuardTest}, and for the same reason.
 *
 * <h2>What is already true, and what is not</h2>
 *
 * <p>Route id {@code exams} has been offered to both teaching roles since E8.6, because E8
 * shipped the approval-status half of this screen behind it. So the role cases below <b>pass
 * today</b> and are here to keep passing: they are what would catch an assembly that swapped the
 * screen and disturbed the offering while doing it. The case that fails today is
 * {@link #theRouteServesTheExamList()}, which is about <b>which class</b> answers to the id.
 *
 * <h2>The retirement is asserted, not assumed ⚑</h2>
 *
 * <p>Contract section 8 requires {@code MY_APPROVALS_GET} and its screen to retire in the same
 * change that lands this one, so there is never a window where two overlapping reads of one fact
 * are both live. The assembly commit is the lead's, and every file the retirement touches is
 * his: {@code MyApprovalsView}, {@code MyApprovalsSession}, {@code ApprovalService}'s
 * registration, {@code common/dto/approval/MyApprovals} and the approval session test. Nothing
 * in Member A's scope can remove them, so the only honest thing this side can do is <b>fail
 * until they are gone</b> rather than describe the retirement as done.
 *
 * <h2>The mutations it has to reject</h2>
 *
 * <p>One assertion is not a guard (method rule 4). The wiring can be wrong in five ways, one
 * test each so a failure names which happened:
 *
 * <ol>
 *   <li>the id resolves to nobody's screen, which is #25;</li>
 *   <li>offered to one teaching role and not the other, which is how a coordinator finds an
 *       empty rail where a teacher finds her exams;</li>
 *   <li>offered to a student, whose every exam-builder verb is refused server-side (F1.2);</li>
 *   <li>offered to the principal, who authors nothing and would get Submit and Revise buttons
 *       that can only fail;</li>
 *   <li>the id still resolving to the screen this one replaces, which would leave a teacher
 *       unable to see her own drafts while every other assertion here passed.</li>
 * </ol>
 */
class ExamListWiringGuardTest {

    /**
     * The id the assembly must point at this screen, read from the feature's own constant.
     *
     * <p>Not a literal: {@link ExamBuildRoutes#LIST} is what every navigation in this feature
     * uses, so pinning the constant is what makes this guard prove the screen is reachable
     * rather than that somebody typed the same string twice.
     */
    private static final String EXAMS_ROUTE_ID = ExamBuildRoutes.LIST;

    /**
     * The rail id, spelled as a literal on purpose.
     *
     * <p>{@link #EXAMS_ROUTE_ID} reads {@code "exams"} too, and pinning the end state against a
     * constant that would move along with a mistake proves nothing. This is the id
     * {@code Routes.EXAMS} has carried since E8.6 and the one
     * {@code NotificationCatalog.ROUTE_EXAMS} navigates to; if {@code ExamBuildRoutes.LIST} is
     * ever pointed somewhere else, the end-state case must fail rather than follow it.
     */
    private static final String RAIL_ROUTE_ID = "exams";

    /**
     * The class this route must stop resolving to, named so its absence is checkable.
     *
     * <p>A retired screen has to be named to be asserted gone, the same way the bank's interim
     * route id had to be.
     */
    private static final String RETIRED_SCREEN = "MyApprovalsView";

    /** Everyone this screen is for: the two roles that may author an exam (contract §2). */
    private static final List<Role> MAY_AUTHOR = List.of(Role.TEACHER, Role.COORDINATOR);

    private static boolean offers(Role role, String routeId) {
        return SessionRoutes.routesFor(role).stream()
                .map(Route::id)
                .anyMatch(id -> Objects.equals(id, routeId));
    }

    @Test
    @DisplayName("the exam list is offered to somebody, which is what #25 was not")
    void theExamListIsOfferedAtAll() {
        boolean anyone = MAY_AUTHOR.stream().anyMatch(role -> offers(role, EXAMS_ROUTE_ID));

        assertThat(anyone)
                .as("No role can navigate to route id '%s'. The screens in this package are "
                        + "built and tested and nobody can open one. SessionRoutes must offer "
                        + "the route and map it to ExamListView.", EXAMS_ROUTE_ID)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR"})
    @DisplayName("both roles that may author an exam are offered the list")
    void everyAuthoringRoleIsOfferedTheList(Role role) {
        assertThat(offers(role, EXAMS_ROUTE_ID))
                .as("%s may author an exam under EXAM_BUILDER_WIRE_CONTRACT section 2, and "
                        + "EXAM_LIST is scoped to the exams she wrote. Offering the screen to "
                        + "one of the two and not the other is how a coordinator finds an empty "
                        + "rail where a teacher finds her exams.", role)
                .isTrue();
    }

    @Test
    @DisplayName("a student is never offered the exam list")
    void studentsAreNotOfferedTheList() {
        assertThat(SessionRoutes.routesFor(Role.STUDENT))
                .as("guard against the guard: an empty route list would make the assertion "
                        + "below pass by vacuity")
                .isNotEmpty();
        assertThat(offers(Role.STUDENT, EXAMS_ROUTE_ID))
                .as("Every builder verb is staff-only. The server would refuse the trip, and "
                        + "F1.2 says the client should not have offered it.")
                .isFalse();
    }

    @Test
    @DisplayName("the principal is never offered the exam list ⚑")
    void thePrincipalIsNotOfferedTheList() {
        assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                .as("guard against the guard: she does have routes")
                .isNotEmpty();
        assertThat(offers(Role.PRINCIPAL, EXAMS_ROUTE_ID))
                .as("EXAM_LIST is author-scoped in the SQL and she authors nothing, so her list "
                        + "would be empty on a good day. The screen also carries Submit and "
                        + "Revise, and F9.3 gives her zero mutating verbs, so every control she "
                        + "could reach here can only fail server-side. Her read of the school's "
                        + "exams is the E15.2 Data screen.")
                .isFalse();
    }

    /**
     * The end state, and the case that is red until the assembly lands.
     *
     * <p>Two assertions rather than one, because "the id is offered" was true before this PR and
     * will be true after any future mis-swap. What is new is <b>which class</b> answers to it,
     * and that the screen it replaces is gone from the route table. A swap that pointed the id
     * at the exam list and left the retired screen registered somewhere else would pass the
     * first and fail the second.
     */
    @Test
    @DisplayName("⚑ rail id 'exams' resolves to ExamListView, and the screen it replaces is gone")
    void theRouteServesTheExamList() {
        for (Role role : MAY_AUTHOR) {
            assertThat(offers(role, RAIL_ROUTE_ID))
                    .as("Rail id '%s' is the exam list's, and has been offered to teaching roles "
                            + "since E8.6. The assembly swaps the screen behind it and leaves the "
                            + "id alone, so %s must still be offered it.", RAIL_ROUTE_ID, role)
                    .isTrue();
        }

        assertThat(buildsTheExamList())
                .as("Rail id '%s' must map to ExamListView in SessionRoutes.builderFor. Until it "
                        + "does, every teacher's My Exams item opens the approval-status half "
                        + "that cannot show her a draft, while every other assertion in this "
                        + "file passes.", RAIL_ROUTE_ID)
                .isTrue();

        assertThat(denseSessionRoutes())
                .as("Contract section 8: %s retires in the SAME change that lands this screen, "
                        + "so there is never a window where two overlapping reads of one fact "
                        + "are both live. Every file that retirement touches is the lead's, so "
                        + "this side can only refuse to go green until it has happened.",
                        RETIRED_SCREEN)
                .doesNotContain(RETIRED_SCREEN);
    }

    /**
     * The retirement is checked where it actually lives, not only in the route table ⚑.
     *
     * <p>A cold read caught this file over-claiming: its own javadoc says it fails "until they
     * are gone" about five artifacts, while the assertion above reads one file and would go
     * green on an assembly that deleted a single line of {@code SessionRoutes} and left the verb,
     * the screen, its session and the DTO all live. That is exactly the window contract §8 says
     * must never exist, passing a guard written to prevent it.
     *
     * <p>The classpath is the honest place to ask: a class that has been deleted cannot be
     * loaded, and a verb that has been removed cannot be resolved by name. {@code Verb} is
     * checked by <b>name</b> rather than by referencing the constant, because referencing it
     * would make this file stop compiling the moment the lead removes it, which takes the whole
     * build down and tells a reader nothing about what was missing. Same reasoning
     * {@code BankScreenWiringGuardTest} gave for reading {@code BankRoutes} instead of
     * {@code client.core.Routes}.
     */
    @Test
    @DisplayName("⚑ the retired verb, screen and session are gone from the build, not just unrouted")
    void theRetirementReachedEveryArtifact() {
        assertThat(onClasspath("client.features.approval.MyApprovalsView"))
                .as("§8: the screen retires with the swap. It is the lead's file, so this only "
                        + "refuses to be green until it has happened.")
                .isFalse();
        assertThat(onClasspath("client.features.approval.MyApprovalsSession"))
                .as("its session goes with it, or a dead read of MY_APPROVALS_GET is still "
                        + "compiled into the client")
                .isFalse();
        assertThat(verbExists("MY_APPROVALS_GET"))
                .as("§8: MY_APPROVALS_GET retires INTO EXAM_LIST. While both are on the wire "
                        + "there are two overlapping reads of one fact, which is the window the "
                        + "same-PR rule exists to close.")
                .isFalse();
    }

    private static boolean onClasspath(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean verbExists(String verbName) {
        return java.util.Arrays.stream(common.protocol.Verb.values())
                .anyMatch(verb -> verb.name().equals(verbName));
    }

    /**
     * Reads the mapping out of {@link SessionRoutes} rather than building the screen.
     *
     * <p>{@code ScreenFactory.get} would answer this directly and is the honest way to ask, but
     * it <em>builds</em>, and {@code ExamListView} creates a {@code DataTable} and a dozen
     * controls in field initialisers, so calling it needs a booted JavaFX toolkit. That would
     * turn a wiring guard into an FX test, for a property that is a line of source.
     *
     * <p>So this uses the shape {@code BankScreenWiringGuardTest} uses, with the same limitation
     * stated: it proves the mapping is <em>written</em>, not that it runs.
     * {@code ExamListInteractionTest} drives the built screen and is where "it actually works"
     * is established. Comments are stripped first, so commenting the branch out reads as
     * deleting it.
     */
    private static boolean buildsTheExamList() {
        return denseSessionRoutes()
                .contains("Routes.EXAMS.id().equals(route.id())){returnExamListView::new;");
    }

    private static String denseSessionRoutes() {
        return readSessionRoutes()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\\s+", "");
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
