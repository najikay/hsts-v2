package server.db.repos;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One definition of "this name looks like an answer key", shared by every E2.12 guard.
 *
 * <p>It lives in one place because the guards are only as good as this predicate, and a
 * second copy would drift. The vocabulary is deliberately wider than {@code correct}: an
 * adversarial review pointed out that a future {@code rightAnswer}, {@code solution} or
 * {@code answerIndex} would sail past a check that only looked for that one word, and the
 * projection whitelist would not notice because it only ever inspects one named class.
 *
 * <p>It must <b>not</b> match {@code answer1}…{@code answer4}, which are the four options a
 * student is supposed to see.
 */
final class CorrectnessNames {

    private static final List<String> SUSPICIOUS = List.of(
            "correct", "iscorrect", "answerkey", "answerindex", "answerno",
            "rightanswer", "solution");

    private CorrectnessNames() {
        // static helper - no instances
    }

    /**
     * @param name a field, component or method name
     * @return whether it reads like it holds which answer is right
     */
    static boolean suggestsCorrectness(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("key") || SUSPICIOUS.stream().anyMatch(lower::contains);
    }

    /**
     * @param type any class
     * @return whether it declares a field that looks like an answer key
     */
    static boolean carriesCorrectness(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .anyMatch(CorrectnessNames::suggestsCorrectness);
    }
}
