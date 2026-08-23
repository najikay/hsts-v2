package server.features.reports;

import java.util.List;

/**
 * The one place a report dimension is registered (Logic tier, E15.3 ⚑ — F9.4, S-37).
 *
 * <p>This class is the "nothing else" in "a new report type is a new strategy class and a menu
 * entry, nothing else". It exists so that the list is <b>one list</b>, findable by name, rather
 * than a set of {@code register} calls spread through an assembly method where a fourth one
 * could be added in three plausible places and forgotten in a fourth.
 *
 * <p>{@link ReportEngine} takes whatever list it is handed and never calls this class, which is
 * what lets a test register a fourth dimension of its own and drive the real engine with it. The
 * structural claim is proven rather than asserted: {@code ReportEngineExtensibilityTest} builds
 * an engine over {@code all()} plus a strategy that exists only in the test file, and gets a
 * working report out of it without touching a wire type, a handler or a screen.
 *
 * <p>The three that ship are the three F9.4 names. They are constructed fresh on every call and
 * they hold no state — a strategy is a pair of query choices, and giving one a field is how a
 * report would eventually answer about the subject somebody asked for a minute ago.
 */
public final class ReportStrategies {

    private ReportStrategies() {
        // static registry - no instances
    }

    /**
     * The dimensions this build serves.
     *
     * <p><b>Adding a report type is adding a line here.</b> The engine keys on
     * {@link DimensionStrategy#dimension()}, so the order of this list changes nothing except
     * which duplicate would be reported first if two strategies ever claimed one dimension —
     * and that is a startup failure, not a silent win for one of them.
     *
     * @return the registered strategies, one per served dimension
     */
    public static List<DimensionStrategy> all() {
        return List.of(
                new ByTeacherStrategy(),
                new ByCourseStrategy(),
                new ByStudentStrategy());
    }
}
