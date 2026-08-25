package server.features.locks;

import common.dto.lock.EntityRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The per-entity-type scope registry (E18.9 — PR20 §5.3).
 *
 * <p>Three properties, and the first one is the whole design decision: an entity type nobody
 * installed a scope for is <b>unfiltered</b>, which runs the opposite way to every guard in
 * {@code Authorization}. That inversion is argued in {@link EntityScopes}'s javadoc and pinned
 * here, because a later reader "fixing" it to fail closed would blank the lock chip on four
 * features at once and see nothing red.
 */
class EntityScopesTest {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;

    private final EntityScopes scopes = new EntityScopes();

    @Nested
    @DisplayName("the uninstalled default")
    class Uninstalled {

        @Test
        @DisplayName("a type nobody scoped is unfiltered, because it made no scoping promise")
        void uninstalledTypesPassEverything() {
            assertThat(scopes.reaches(EntityRef.EXAM_VERSION, DANA, 7L)).isTrue();
            assertThat(scopes.reaches(EntityRef.BOT_SOURCE, DANA, 7L)).isTrue();
            assertThat(scopes.reaches(EntityRef.EXECUTION, DANA, 7L)).isTrue();
            assertThat(scopes.reaches(EntityRef.GRADE, DANA, 7L)).isTrue();
        }

        @Test
        @DisplayName("installing one type leaves every other type unfiltered")
        void installingOneTypeDoesNotScopeTheRest() {
            scopes.install(EntityRef.QUESTION, EntityScopes.EntityScope.NOTHING);

            assertThat(scopes.reaches(EntityRef.QUESTION, DANA, 7L))
                    .as("the type that opted in is filtered")
                    .isFalse();
            assertThat(scopes.reaches(EntityRef.EXAM_VERSION, DANA, 7L))
                    .as("E7's locks must not inherit the bank's idea of scope")
                    .isTrue();
        }

        @Test
        @DisplayName("'filtered to nothing' and 'not filtered' are distinguishable, but not by reaches")
        void installationIsVisibleSeparately() {
            assertThat(scopes.isInstalled(EntityRef.QUESTION)).isFalse();

            scopes.install(EntityRef.QUESTION, EntityScopes.EntityScope.NOTHING);

            assertThat(scopes.isInstalled(EntityRef.QUESTION)).isTrue();
            // reaches() answers false in both worlds - unscoped-and-refused looks exactly like
            // scoped-and-refused - which is why isInstalled exists at all.
            assertThat(scopes.reaches(EntityRef.QUESTION, DANA, 7L)).isFalse();
        }
    }

    @Nested
    @DisplayName("installation")
    class Installation {

        @Test
        @DisplayName("the installed predicate is consulted with the caller and the entity")
        void thePredicateSeesBothIds() {
            scopes.install(EntityRef.QUESTION,
                    (callerId, entityId) -> callerId == DANA && entityId == 11001L);

            assertThat(scopes.reaches(EntityRef.QUESTION, DANA, 11001L)).isTrue();
            assertThat(scopes.reaches(EntityRef.QUESTION, DANA, 21001L))
                    .as("a different entity is a different answer")
                    .isFalse();
            assertThat(scopes.reaches(EntityRef.QUESTION, RINA, 11001L))
                    .as("a different caller is a different answer")
                    .isFalse();
        }

        @Test
        @DisplayName("install returns what was there before, so a caller can put it back")
        void installReturnsThePrevious() {
            EntityScopes.EntityScope first = (callerId, entityId) -> true;

            assertThat(scopes.install(EntityRef.QUESTION, first))
                    .as("nothing was installed, so there is nothing to hand back")
                    .isNull();
            assertThat(scopes.install(EntityRef.QUESTION, EntityScopes.EntityScope.NOTHING))
                    .isSameAs(first);
        }

        @Test
        @DisplayName("installing null removes the scope and the type goes back to unfiltered")
        void nullUninstalls() {
            scopes.install(EntityRef.QUESTION, EntityScopes.EntityScope.NOTHING);

            scopes.install(EntityRef.QUESTION, null);

            assertThat(scopes.isInstalled(EntityRef.QUESTION)).isFalse();
            assertThat(scopes.reaches(EntityRef.QUESTION, DANA, 7L)).isTrue();
        }

        @Test
        @DisplayName("the type is normalised the same way EntityRef normalises it")
        void typeIsNormalised() {
            // The hazard this closes: a scope installed under a spelling nothing looks up is a
            // filter that looks installed in the wiring and passes everything at runtime.
            scopes.install("  Question  ", EntityScopes.EntityScope.NOTHING);

            assertThat(scopes.reaches(EntityRef.QUESTION, DANA, 7L)).isFalse();
            assertThat(scopes.reaches("QUESTION", DANA, 7L)).isFalse();
            assertThat(scopes.isInstalled("question")).isTrue();
        }

        @Test
        @DisplayName("a blank or missing type is refused rather than registered under nothing")
        void typeIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> scopes.install(null, EntityScopes.EntityScope.NOTHING));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> scopes.install("   ", EntityScopes.EntityScope.NOTHING));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> scopes.reaches("", DANA, 7L));
        }
    }

    @Nested
    @DisplayName("the EntityRef overload")
    class ByReference {

        @Test
        @DisplayName("it asks the same question as the string form")
        void theOverloadAgrees() {
            scopes.install(EntityRef.QUESTION, (callerId, entityId) -> entityId == 11001L);

            assertThat(scopes.reaches(EntityRef.question(11001L), DANA)).isTrue();
            assertThat(scopes.reaches(EntityRef.question(21001L), DANA)).isFalse();
        }

        @Test
        @DisplayName("an entity is required")
        void entityIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> scopes.reaches((EntityRef) null, DANA));
        }
    }
}
