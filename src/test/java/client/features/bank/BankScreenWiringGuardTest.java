package client.features.bank;

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
 * The bank screen is reachable, or this fails (E6.9 ⚑).
 *
 * <p>This is the client half of {@code BankWiringGuardTest}, and it exists for the same reason
 * that one does. PR #25 merged a question bank whose verbs compiled, whose unit tests passed,
 * and which no running server could reach, because nothing called {@code registerOn}. The screens
 * in this package are in exactly that position: they are registered by
 * {@link SessionRoutes}, which is outside Member A's scope, so the code in this feature can be
 * complete, green and unreachable at the same time.
 *
 * <p><b>This test was expected to fail until the assembly landed</b>, and that was the point. A
 * feature PR that goes green while its screen is not on any rail is the failure; a feature PR
 * that refuses to go green until somebody wires it is the guard working. Both assemblies have
 * since landed and the retirement after them, so every case below now guards rather than waits.
 *
 * <h2>Why it reads {@link BankRoutes} and not {@code client.core.Routes}</h2>
 *
 * <p>Written when {@code Routes.BANK} did not exist: naming it would have made this a
 * <b>compile</b> error rather than a test failure, taking the whole build down and telling a
 * reader nothing about what was missing. {@link BankRoutes} is the feature's own copy of the two
 * spellings, used by every button and every navigation in the package, so pinning it proves the
 * screens are reachable rather than that somebody typed one string twice.
 * {@code AppArgsAndRoutesTest.notificationRoutesLineUp} pins the notification route ids for the
 * same reason.
 *
 * <p>The end-state case at the bottom is the exception and reads literals instead — see its own
 * note. A constant that would move along with the mistake cannot pin the mistake.
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
 * <p>Plus one that belonged to the sequencing rather than to either screen: the legacy screen
 * leaving the rail before its replacement was on it, which would have taken the question bank
 * away from every teacher for the length of the gap. That is what the lead's ruling of
 * 2026-08-23 prevented — rail id {@code questions} kept serving the legacy screen until the
 * retirement PR swapped the screen and left the id alone. The sequencing case has been replaced
 * by the end-state case it was protecting: the rail id resolves to {@code BankView}, and the
 * interim id is gone.
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
 * <p><b>The four negative cases passed vacuously until the assembly landed</b>, because neither
 * route was offered to anybody. They are live now — both routes exist and are offered to the two
 * authoring roles — so the vacuity assertion in each has stopped being the only thing it checks.
 * The assertions are kept rather than removed: they are what would catch a future role list that
 * lost its shape, and a guard against vacuity costs one line.
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

    /**
     * The rail id, spelled as a literal on purpose.
     *
     * <p>{@link #BANK_ROUTE_ID} now reads {@code "questions"} too, and pinning the end state
     * against a constant that would move with a mistake proves nothing. This is the id a
     * teacher's rail item has carried since E5.4 and the one the lead's ruling of 2026-08-23
     * protected through the retirement; if {@code BankRoutes.LIST} is ever pointed somewhere
     * else, the assertions below must fail rather than follow it.
     */
    private static final String RAIL_ROUTE_ID = "questions";

    /**
     * The interim id, kept as a literal so its absence is checkable.
     *
     * <p>It was {@code BankRoutes.LIST}'s value while the legacy screen held the rail, and there
     * is no constant left to read it from — which is the point: a retired id has to be named to
     * be asserted gone.
     */
    private static final String INTERIM_ROUTE_ID = "bank";

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

    /**
     * The end state, pinned so the two ids cannot quietly split again (retirement PR).
     *
     * <p>This replaces {@code theLegacyScreenIsStillReachable}, which asserted the same rail id
     * was serving the <em>legacy</em> screen and existed to stop that screen leaving the rail
     * before its replacement arrived. Both halves of that ruling are now discharged: the rail id
     * survived and the screen behind it is the versioned bank.
     *
     * <p>Three assertions rather than one, because "the id is offered" was true before this PR
     * too and would be true again under any future re-split. What is new is <b>which class</b>
     * answers to it, and that the interim id is gone — a change that re-introduced {@code "bank"}
     * as a second bank route would pass the first assertion and fail the third.
     */
    @Test
    @DisplayName("⚑ rail id 'questions' resolves to BankView for both teaching roles")
    void theRailIdServesTheVersionedBank() {
        for (Role role : MAY_BROWSE) {
            assertThat(offers(role, RAIL_ROUTE_ID))
                    .as("Rail id '%s' is the question bank's, and has been since E5.4. The "
                            + "retirement PR swapped the screen behind it and left the id alone, "
                            + "so %s must still be offered it.", RAIL_ROUTE_ID, role)
                    .isTrue();

        }

        assertThat(buildsTheVersionedBank())
                .as("Rail id '%s' must map to BankView in SessionRoutes.builderFor. Before the "
                        + "retirement PR it mapped to the E0 prototype list; if it is ever pointed "
                        + "at anything else, every teacher's Question Bank item opens a different "
                        + "screen while every assertion about the id above still passes.",
                        RAIL_ROUTE_ID)
                .isTrue();

        assertThat(SessionRoutes.routesFor(Role.TEACHER)).extracting(Route::id)
                .as("The interim id '%s' existed only so the versioned bank could be reached "
                        + "while the legacy screen still held the rail. Both are retired, and a "
                        + "second live id for one screen is how the two drift apart again.",
                        INTERIM_ROUTE_ID)
                .doesNotContain(INTERIM_ROUTE_ID)
                .contains(RAIL_ROUTE_ID);
    }

    /**
     * Reads the mapping out of {@link SessionRoutes} rather than building the screen.
     *
     * <p>{@code ScreenFactory.get} would answer this directly and is the honest way to ask, but
     * it <em>builds</em>: {@code BankView} creates its {@code VBox}, its {@code DataTable} and a
     * dozen controls in field initialisers, so calling it needs a booted JavaFX toolkit. That
     * would turn a wiring guard into an FX test, for a property that is a line of source.
     *
     * <p>So this uses the shape {@code BankWiringGuardTest} uses on the server assembly, for the
     * same reason and with the same limitation stated: it proves the mapping is <em>written</em>,
     * not that it runs. {@code BankScreenInteractionTest} drives the built screen and is where
     * "it actually works" is established. Comments are stripped first, so commenting the branch
     * out reads as deleting it.
     */
    private static boolean buildsTheVersionedBank() {
        String dense = readSessionRoutes()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\\s+", "");

        return dense.contains("Routes.QUESTIONS.id().equals(route.id())){returnBankView::new;");
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
