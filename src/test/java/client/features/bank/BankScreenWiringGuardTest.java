package client.features.bank;

import client.core.Route;
import client.core.SessionRoutes;
import common.dto.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bank screen is reachable, or this fails (E6.9 ⚑).
 *
 * <p>This is the client half of {@code BankWiringGuardTest}, and it exists for the same reason
 * that one does. PR #25 merged a question bank whose verbs compiled, whose unit tests passed,
 * and which no running server could reach, because nothing called {@code registerOn}. The screens
 * in this package are in exactly that position: they are registered by
 * {@link SessionRoutes}, which is outside Member A's scope, so the code in this feature can be
 * complete, green and unreachable at the same time.
 *
 * <p><b>So this test is expected to fail until the assembly lands.</b> That is the point. A
 * feature PR that goes green while its screen is not on any rail is the failure; a feature PR
 * that refuses to go green until somebody wires it is the guard working.
 *
 * <h2>Why it reads {@link BankRoutes} and not {@code client.core.Routes}</h2>
 *
 * <p>{@code Routes.BANK} does not exist yet, and naming it would make this a <b>compile</b> error
 * rather than a test failure: the whole build would go down and tell a reader nothing about what
 * is missing. {@link BankRoutes} is the feature's own copy of the two spellings, used by every
 * button and every navigation in the package, so pinning it proves the screens are reachable
 * rather than that somebody typed one string twice.
 * {@code AppArgsAndRoutesTest.notificationRoutesLineUp} pins the notification route ids for the
 * same reason.
 *
 * <h2>The mutations it has to reject</h2>
 *
 * <p>One assertion is not a guard (method rule 4). Two screens, and each of them can be wired
 * wrong in the same four ways, one test each so a failure names which one happened:
 *
 * <ol>
 *   <li>registered for nobody, which is #25 exactly (the list only; the editor's absence is
 *       covered by its own positive case);</li>
 *   <li>registered for one authoring role and not the other, which is how a coordinator finds an
 *       empty rail where a teacher finds a bank;</li>
 *   <li>offered to a student, which is a trip the server would refuse and the client should
 *       never have offered (F1.2);</li>
 *   <li>offered to the principal, which hands an S-7 role a Delete button on the list and a Save
 *       on the editor that can only be refused.</li>
 * </ol>
 *
 * <p>Plus one that belongs to the sequencing rather than to either screen: the legacy screen
 * leaving the rail before its replacement is on it, which would take the question bank away from
 * every teacher for the length of the gap. That is the one this PR stack actually risks, and it
 * is why the lead ruled that rail id {@code questions} keeps serving the legacy screen until the
 * retirement PR.
 *
 * <h2>Why the principal is a negative case, when the contract lets her read the bank</h2>
 *
 * <p>She is on all four read verbs, and this screen is still not hers. <b>The wire licence and
 * the UI offering are different questions</b> (the lead's ruling on #41): her {@code BANK_LIST}
 * licence exists to feed the E15.2 Data screen, which is her read-only surface and whose own
 * interaction test asserts that no writing control exists anywhere under {@code .principal-data}.
 * {@code BankView} carries Delete today and Edit in the next PR, so registering the S-7 role for
 * it would hand her controls whose only possible outcome is a server refusal.
 *
 * <p>This file had it the other way round first, reasoning from the verb list. That is the same
 * offering-versus-permission distinction case 3 above already makes about students, applied
 * inconsistently one role over.
 *
 * <p><b>All four negative cases pass vacuously until the assembly lands</b>, because neither
 * route is offered to anybody yet. Said out loud rather than left for a reader to assume
 * otherwise: they are green today for the same reason the positives are red, and they only start
 * guarding anything the moment the routes exist. The vacuity assertion in each is the most that
 * can be checked before then.
 */
class BankScreenWiringGuardTest {

    /**
     * The id the assembly PR must register, read from the feature's own constant.
     *
     * <p>Not a literal: {@link BankRoutes#LIST} is what every button and every navigation in the
     * feature uses, so pinning the constant is what makes this guard prove the screens are
     * reachable rather than that somebody typed the same string twice.
     */
    private static final String BANK_ROUTE_ID = BankRoutes.LIST;

    /** The editor's id, on the same terms. */
    private static final String EDITOR_ROUTE_ID = BankRoutes.EDITOR;

    /** The rail id the legacy screen keeps until the retirement PR (the lead's ruling). */
    private static final String LEGACY_ROUTE_ID = "questions";

    /**
     * Everyone this SCREEN is for: the two roles that may write into the bank.
     *
     * <p>Not the contract's read column, which is wider by one. See the class javadoc: the
     * principal reads the bank through the Data screen, and a screen carrying Delete is not a
     * read-only surface however read-only her verbs are.
     */
    private static final List<Role> MAY_BROWSE = List.of(Role.TEACHER, Role.COORDINATOR);

    private static boolean offers(Role role, String routeId) {
        return SessionRoutes.routesFor(role).stream()
                .map(Route::id)
                .anyMatch(id -> Objects.equals(id, routeId));
    }

    @Test
    @DisplayName("the bank screen is registered for somebody, which is what #25 was not")
    void theBankIsRegisteredAtAll() {
        boolean anyone = MAY_BROWSE.stream().anyMatch(role -> offers(role, BANK_ROUTE_ID));

        assertThat(anyone)
                .as("No role can navigate to route id '%s'. The screens in this package are "
                        + "built and tested and nobody can open one. This is #25 on the client "
                        + "side: SessionRoutes must register the route and map it to BankView.",
                        BANK_ROUTE_ID)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR"})
    @DisplayName("both roles that may write into the bank are offered the screen")
    void everyAuthoringRoleIsOfferedTheBank(Role role) {
        assertThat(offers(role, BANK_ROUTE_ID))
                .as("%s may write into the bank under BANK_WIRE_CONTRACT section 2, so the "
                        + "screen that writes is hers. Registering it for one of the two and not "
                        + "the other is how a coordinator finds an empty rail where a teacher "
                        + "finds a bank.", role)
                .isTrue();
    }

    @Test
    @DisplayName("the principal is never offered the bank screen, read licence or not ⚑")
    void thePrincipalIsNotOfferedTheBank() {
        assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                .as("guard against the guard: an empty route list would make the assertion "
                        + "below pass by vacuity")
                .isNotEmpty();
        assertThat(offers(Role.PRINCIPAL, BANK_ROUTE_ID))
                .as("She is on all four bank READ verbs, and this screen is still not hers: it "
                        + "carries Delete, and Edit next. S-7 gives her zero mutating verbs, so "
                        + "every control she could reach here can only fail server-side. Her "
                        + "browse is the E15.2 Data screen, whose own test asserts it holds no "
                        + "writing control at all. The licence and the offering are different "
                        + "questions (lead's ruling on #41).")
                .isFalse();
    }

    @Test
    @DisplayName("a student is never offered the bank")
    void studentsAreNotOfferedTheBank() {
        assertThat(SessionRoutes.routesFor(Role.STUDENT))
                .as("guard against the guard: an empty route list would make the assertion "
                        + "below pass by vacuity")
                .isNotEmpty();
        assertThat(offers(Role.STUDENT, BANK_ROUTE_ID))
                .as("A student has no bank verb at all: every one of the seven is staff-only. "
                        + "The server would refuse the trip, and F1.2 says the client should not "
                        + "have offered it.")
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR"})
    @DisplayName("the editor is offered to the two roles that may write, and to nobody else")
    void theEditorIsOfferedToAuthorsOnly(Role role) {
        assertThat(offers(role, EDITOR_ROUTE_ID))
                .as("%s may write into the bank under BANK_WIRE_CONTRACT section 2, so she needs "
                        + "the editor route. Without it the Edit button navigates nowhere.", role)
                .isTrue();
    }

    @Test
    @DisplayName("the principal is never offered the editor, because she may never write ⚑")
    void thePrincipalIsNotOfferedTheEditor() {
        assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                .as("guard against vacuity: she does have routes")
                .isNotEmpty();
        assertThat(offers(Role.PRINCIPAL, EDITOR_ROUTE_ID))
                .as("F9.3 gives her zero mutating verbs, ever. She is on the bank's four READ "
                        + "verbs and on none of the three writes, so offering her an editor would "
                        + "be a screen whose every Save is refused. This is the one row of "
                        + "section 2's table that a client can get wrong on its own.")
                .isFalse();
    }

    @Test
    @DisplayName("a student is offered neither screen")
    void studentsAreOfferedNeither() {
        assertThat(SessionRoutes.routesFor(Role.STUDENT))
                .as("guard against vacuity: a student does have routes")
                .isNotEmpty();
        // Both, because the name says both. It checked only the editor until a rebase put the
        // bank's own student case beside it and left this one over-claiming by half.
        assertThat(offers(Role.STUDENT, EDITOR_ROUTE_ID)).isFalse();
        assertThat(offers(Role.STUDENT, BANK_ROUTE_ID)).isFalse();
    }

    @Test
    @DisplayName("the legacy screen keeps its rail id until the retirement PR")
    void theLegacyScreenIsStillReachable() {
        assertThat(offers(Role.TEACHER, LEGACY_ROUTE_ID))
                .as("Rail id '%s' must keep serving the legacy screen until E6's last PR swaps "
                        + "it (the lead's ruling, 2026-08-23). Removing it before the "
                        + "replacement is on the rail takes the question bank away from every "
                        + "teacher for the length of the gap.", LEGACY_ROUTE_ID)
                .isTrue();
    }
}
