package common.dto.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-34, enforced on the type rather than on the screen (E16.10 ⚑ — F12.11).
 *
 * <h2>What it guards</h2>
 *
 * <p>"No student identities anywhere in that view <b>or its DTOs</b>" is a
 * requirement about a shape, so it is checked as one. Starting from
 * {@link BotAnalytics} — the payload of {@code BOT_ANALYTICS_GET} — this walks
 * every record component it can reach, including through {@code List<...>}, and
 * asserts that none of them is named like an identity.
 *
 * <h2>Why it walks rather than naming three types</h2>
 *
 * <p>The cheapest way to break S-34 is not to add a field to {@code BotAnalytics};
 * it is to add one to {@link BotTopQuestion} — "who asked this most" is a
 * genuinely tempting feature — or to introduce a fourth type below it. A test that
 * inspected only the top-level record would stay green through both. This one
 * fails the moment an identity-shaped name appears anywhere in the reachable
 * graph, which is the moment it can be discussed rather than the moment it ships.
 *
 * <h2>What it cannot do</h2>
 *
 * <p>It checks names, not intent: a component called {@code note} could be filled
 * with a student's name by a determined mapper. The other two layers are what stop
 * that — the SQL never selects the column ({@code BotRepository.findActivity} and
 * friends), and {@code BotAdminServiceTest} pins what the service actually builds.
 * Three independent checks on one requirement is the right number for the one that
 * is a promise to students.
 */
class BotAnalyticsIdentityGuardTest {

    /**
     * Name fragments that read like a person.
     *
     * <p>Wider than "student" on purpose, and for the reason {@code CorrectnessNames}
     * is wider than "correct": a future {@code askerId}, {@code userName} or
     * {@code sessionId} would sail past a check that only looked for the one word
     * somebody happened to think of first.
     */
    private static final List<String> IDENTITY_WORDS = List.of(
            "student", "user", "person", "pupil", "learner", "asker", "author",
            "session", "attempt", "email", "national", "username", "fullname",
            "displayname", "identity", "who");

    @Test
    @DisplayName("nothing reachable from the analytics payload is named like an identity ⚑")
    void analyticsCarryNoIdentity() {
        List<String> offenders = new ArrayList<>();

        for (String component : reachableComponents(BotAnalytics.class)) {
            if (suggestsIdentity(component)) {
                offenders.add(component);
            }
        }

        assertThat(offenders)
                .as("S-34: the teacher's aggregate is anonymous, and the DTOs are how that is "
                        + "guaranteed rather than hoped for. If a component here genuinely needs "
                        + "to identify somebody, it belongs on a different verb with a different "
                        + "authorisation story.")
                .isEmpty();
    }

    @Test
    @DisplayName("the walk really does reach the nested types, not just the top one")
    void theWalkHasTeeth() {
        Set<String> components = reachableComponents(BotAnalytics.class);

        assertThat(components)
                .contains("courseName", "totalQuestions", "activity", "frequent")
                .as("through List<BotActivityPoint>")
                .contains("day", "count")
                .as("through List<BotTopQuestion>")
                .contains("question");
    }

    @Test
    @DisplayName("the predicate would catch the fields somebody would actually be tempted to add")
    void thePredicateHasTeeth() {
        assertThat(suggestsIdentity("studentId")).isTrue();
        assertThat(suggestsIdentity("userId")).isTrue();
        assertThat(suggestsIdentity("askedBy")).isFalse();
        assertThat(suggestsIdentity("asker")).isTrue();
        assertThat(suggestsIdentity("sessionId")).isTrue();
        assertThat(suggestsIdentity("studentName")).isTrue();
        assertThat(suggestsIdentity("attemptId")).isTrue();

        assertThat(suggestsIdentity("courseName"))
                .as("a course is not a person")
                .isFalse();
        assertThat(suggestsIdentity("totalQuestions")).isFalse();
        assertThat(suggestsIdentity("count")).isFalse();
        assertThat(suggestsIdentity("day")).isFalse();
    }

    @Test
    @DisplayName("the analytics types are records, so their whole shape is their components")
    void everythingReachableIsARecord() {
        assertThat(BotAnalytics.class.isRecord()).isTrue();
        assertThat(BotActivityPoint.class.isRecord()).isTrue();
        assertThat(BotTopQuestion.class.isRecord()).isTrue();
    }

    /**
     * @param root a record type
     * @return every record component name reachable from it, following record
     *         components and the type arguments of their generic types
     */
    private static Set<String> reachableComponents(Class<?> root) {
        Set<String> names = new LinkedHashSet<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (!type.isRecord() || !visited.add(type)) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName());
                queue.add(component.getType());
                Type generic = component.getGenericType();
                if (generic instanceof ParameterizedType parameterized) {
                    for (Type argument : parameterized.getActualTypeArguments()) {
                        if (argument instanceof Class<?> argumentType) {
                            queue.add(argumentType);
                        }
                    }
                }
            }
        }
        return names;
    }

    /**
     * @param name a record component name
     * @return whether it reads like it identifies a person
     */
    private static boolean suggestsIdentity(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return IDENTITY_WORDS.stream().anyMatch(lower::contains);
    }
}
