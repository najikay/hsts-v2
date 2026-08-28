package client.net;

import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FX-thread seam, enforced by scanning rather than by review (M-4, 2026-08-28).
 *
 * <h2>What this test is for</h2>
 *
 * <p>{@link RequestDispatcher} completes its futures on whichever thread delivered the
 * outcome, which in production is OCSF's socket read thread — its own javadoc says the FX
 * hop belongs to {@link FxThreadPoster}. Every session honoured that by convention, except
 * the two exam sessions and the four bot sessions, and the convention had no tripwire. The
 * cost was M-4: a student met a blank screen because the whole paper render ran on the read
 * thread, the {@code IllegalStateException} from the toolkit's thread check completed a
 * future nobody observed, and every test was green because {@code FakeClientConnection}
 * delivers on the caller's thread. A seam that mocks away the thread mocks away the bug.
 *
 * <h2>How it checks</h2>
 *
 * <p>Every compiled class under {@code client} that holds a {@link RequestDispatcher} field
 * must also hold the seam — an {@link FxThreadPoster} or a {@link ClientEventBus} (whose
 * {@code poster()} is the same seam). Classes are loaded without initialisation, so no
 * toolkit starts.
 *
 * <h2>The honest limit, demonstrated the day this was written</h2>
 *
 * <p>Holding the poster does not prove every response is applied through it. Three sessions
 * (notifications, monitor, release) held the seam and settled raw anyway — M-1 and M-5 among
 * them — and this guard would have been green over all three. Possession is what a field scan
 * can see; use is what the reader-thread reproduction in {@code M4ReproTest} pins for the
 * screen that failed, and what review owes every new send site. What this guard catches is
 * the gross omission — a whole session written without the seam — which is the shape six of
 * P-14's nine defects had.
 */
class DispatcherThreadSeamGuardTest {

    private static final Path COMPILED_CLIENT = Path.of("target", "classes", "client");

    /**
     * Carriers, not settlers: classes that hold a dispatcher only to build it or hand it
     * over, with no response callback of their own. Each name here is a claim that the
     * class settles nothing, and adding to this list is a review event, not a convenience.
     *
     * <ul>
     *   <li>{@code ConnectWiring$Wiring} — the record the connect screen returns; it
     *       carries the freshly built dispatcher to {@code ScreenManager} and reads no
     *       response ever.</li>
     * </ul>
     */
    private static final List<String> CARRIERS =
            List.of("client.features.connect.ConnectWiring$Wiring");

    @Test
    @DisplayName("every client class holding a RequestDispatcher also holds the FX-thread seam")
    void everyDispatcherHolderCarriesThePoster() {
        List<String> missing = new ArrayList<>();
        for (Class<?> type : compiledClientClasses()) {
            boolean holdsDispatcher = false;
            boolean holdsSeam = false;
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                if (field.getType() == RequestDispatcher.class) {
                    holdsDispatcher = true;
                }
                if (field.getType() == FxThreadPoster.class
                        || field.getType() == ClientEventBus.class) {
                    holdsSeam = true;
                }
            }
            if (holdsDispatcher && !holdsSeam
                    && type != RequestDispatcher.class
                    && !CARRIERS.contains(type.getName())) {
                missing.add(type.getName());
            }
        }
        assertThat(missing)
                .as("these classes settle server responses with no way onto the FX thread; "
                        + "give them the FxThreadPoster (or the ClientEventBus that carries "
                        + "one) and apply every response through it, as M-4 requires")
                .isEmpty();
    }

    private static List<Class<?>> compiledClientClasses() {
        assertThat(COMPILED_CLIENT)
                .as("compiled client classes exist; run a compile first")
                .exists();
        try (Stream<Path> files = Files.walk(COMPILED_CLIENT)) {
            List<Class<?>> classes = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = COMPILED_CLIENT.getParent().relativize(file).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                try {
                    classes.add(Class.forName(name, false,
                            DispatcherThreadSeamGuardTest.class.getClassLoader()));
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // A class the test classpath cannot see proves nothing either way.
                }
            }
            return classes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
