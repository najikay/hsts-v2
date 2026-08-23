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
 * <h2>Why it matches on the id string and not on a {@code Routes} constant</h2>
 *
 * <p>{@code Routes.BANK} does not exist yet either, and referring to it would make this a
 * <b>compile</b> error rather than a test failure, which would take the whole build down and
 * tell a reader nothing about what is missing. The id literal is also the thing the assembly has
 * to spell correctly, so pinning the spelling is the useful half:
 * {@code AppArgsAndRoutesTest.notificationRoutesLineUp} pins the notification route ids the same
 * way and for the same reason.
 *
 * <h2>The mutations it has to reject</h2>
 *
 * <p>One assertion is not a guard (method rule 4). Four separate ways the wiring can be wrong,
 * one test each, so a failure names which one happened:
 *
 * <ol>
 *   <li>the route is registered for nobody, which is #25 exactly;</li>
 *   <li>it is registered for some teaching roles and not others, which is how a coordinator
 *       finds an empty rail where a teacher finds a bank;</li>
 *   <li>it is offered to a student, which is a trip the server would refuse and the client
 *       should never have offered (F1.2);</li>
 *   <li>the legacy screen leaves the rail before its replacement is on it, which would take the
 *       bank away from everyone for as long as the gap lasts.</li>
 * </ol>
 *
 * <p>The fourth is the one this PR's sequencing actually risks, and it is the reason the lead
 * ruled that rail id {@code questions} stays on the legacy screen until the retirement PR.
 */
class BankScreenWiringGuardTest {

    /**
     * The id the assembly PR must register, declared once.
     *
     * <p>Named in this PR's body under "route id" so the assembly and this constant are two
     * copies of one decision that a reviewer can compare in one glance.
     */
    private static final String BANK_ROUTE_ID = "bank";

    /** The rail id the legacy screen keeps until the retirement PR (the lead's ruling). */
    private static final String LEGACY_ROUTE_ID = "questions";

    /** Everyone who may browse the bank, per BANK_WIRE_CONTRACT section 2's read column. */
    private static final List<Role> MAY_BROWSE =
            List.of(Role.TEACHER, Role.COORDINATOR, Role.PRINCIPAL);

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
    @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR", "PRINCIPAL"})
    @DisplayName("every role the contract lets read the bank is offered the screen")
    void everyReadingRoleIsOfferedTheBank(Role role) {
        assertThat(offers(role, BANK_ROUTE_ID))
                .as("%s may read the bank under BANK_WIRE_CONTRACT section 2 (the four read "
                        + "verbs add PRINCIPAL to the two teaching roles), so the client must "
                        + "offer her the trip. Registering it for some of the three and not the "
                        + "others is how one role finds an empty rail.", role)
                .isTrue();
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
