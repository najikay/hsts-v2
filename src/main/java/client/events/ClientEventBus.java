package client.events;

import org.greenrobot.eventbus.EventBus;

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * The client-side publish/subscribe hub (Presentation tier, ADR-007).
 *
 * <p>Wraps greenrobot {@link EventBus} and pairs it with an
 * {@link FxThreadPoster}: <b>every</b> event is delivered through the poster, so
 * subscribers — screens — always run on the JavaFX Application Thread no matter
 * which thread produced the event. That is the single crossing point required by
 * ARCHITECTURE §6; screens therefore never call {@code Platform.runLater}
 * themselves, and tests swap in {@link DirectFxThreadPoster} to run the whole
 * chain synchronously without a toolkit.
 *
 * <p>{@link #getInstance()} is the app-wide holder (Singleton); the public
 * constructor exists so tests — and any future second window — can own an
 * isolated bus instead of sharing global state.
 */
public final class ClientEventBus {

    private static volatile ClientEventBus instance;

    /**
     * The stable half of the P-10 refusal, so a test can assert the sentence a developer
     * reads without pinning a class name that moves when a test class is renamed.
     */
    static final String NOT_PUBLIC_PREFIX = "@Subscribe classes must be public: ";

    private final EventBus bus;
    private final FxThreadPoster poster;

    public ClientEventBus(EventBus bus, FxThreadPoster poster) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** @return the application-wide bus, created on first use with the real FX poster. */
    public static ClientEventBus getInstance() {
        ClientEventBus local = instance;
        if (local == null) {
            synchronized (ClientEventBus.class) {
                local = instance;
                if (local == null) {
                    local = new ClientEventBus(newBus(), new PlatformFxThreadPoster());
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * A bus tuned for this app: an event with no subscribers is normal here (a
     * push can arrive while its screen is closed), so it is neither logged as a
     * warning nor re-published as a {@code NoSubscriberEvent}.
     */
    public static EventBus newBus() {
        return EventBus.builder()
                .logNoSubscriberMessages(false)
                .sendNoSubscriberEvent(false)
                .build();
    }

    /**
     * Subscribes an object's {@code @Subscribe} methods.
     *
     * <p><b>Refuses a subscriber greenrobot could never deliver to, at registration (P-10).</b>
     * It invokes {@code @Subscribe} methods reflectively, and reflection needs the
     * <em>class</em> to be accessible as well as the method. A {@code public} method on a
     * package-private class registers perfectly happily and then throws
     * {@link IllegalAccessException} on every single delivery — which
     * {@code RequestDispatcher} catches and logs on purpose, so that one broken subscriber
     * cannot take the socket down with it.
     *
     * <p>The result is the worst shape a defect can have: a screen that paints once, never
     * updates again, throws nothing a user sees, and leaves the suite green. Tests construct
     * their subscribers directly and post to them, so the reflective path is never the one
     * under test. Member A hit this while building the bank list's live chip, then audited all
     * fourteen {@code @Subscribe} classes in the tree and found every one already
     * {@code public final} — so this guard is here for the fifteenth, not to fix a live bug.
     *
     * <p>It throws at <b>registration</b> rather than logging at delivery because registration
     * is where the developer is looking. The first delivery may be minutes later, on another
     * thread, in a log nobody is reading; {@code start()} is in the stack trace here.
     *
     * @param subscriber the screen or component to subscribe
     * @throws IllegalArgumentException when its class is not reflectively reachable from
     *         another package — naming the class and what to do about it
     */
    public void register(Object subscriber) {
        requirePubliclyReachable(subscriber);
        bus.register(subscriber);
    }

    /**
     * The P-10 check: is this class one greenrobot can actually invoke a method on?
     *
     * <h2>The subscriber's own class, and deliberately not its enclosing ones</h2>
     *
     * <p>The obvious version of this guard walks {@link Class#getEnclosingClass()} on the
     * theory that a public nested class inside a package-private outer one is unreachable
     * too. <b>It is not, and that was measured rather than reasoned about.</b> A nested class
     * compiles to its own class file, and javac gives a {@code public} member class
     * {@code ACC_PUBLIC} in that file's own access flags; the JVM's access check reads those
     * and never consults the outer class. Cross-package reflective invocation on a public
     * class nested in a package-private one succeeds.
     *
     * <p>This is not a theoretical correction. The walking version was written first, and it
     * rejected {@code ClientEventBusTest.RecordingSubscriber} — a {@code public static} class
     * inside a package-private test class — and turned five passing tests red. Those tests
     * post through the real bus reflectively and were green before, which is the measurement:
     * the shape the walk called unreachable had been delivering events all along.
     *
     * <h2>Why {@code protected} counts as reachable</h2>
     *
     * <p>Same reason, one step further, and it is the case where every Java-level API lies.
     * A {@code protected} nested class also gets {@code ACC_PUBLIC} in its class file, so the
     * JVM lets greenrobot invoke it — but both {@link Class#getModifiers()} and
     * {@code Class.accessFlags()} report it as {@code protected}, because those read the
     * {@code InnerClasses} attribute, which keeps the source-level modifier. Testing only for
     * {@code public} would therefore refuse a subscriber that works, and a guard that breaks
     * a working screen at startup is worse than the silence it was written to prevent.
     * Accepting both is what makes this predicate match what the JVM actually does.
     *
     * <p>Anonymous and local classes report no modifier at all and are genuinely unreachable,
     * so they are refused — which is the right answer, and the one the naive check also gets.
     *
     * <p>All of this assumes the classpath rather than the module path; nothing in this build
     * declares a {@code module-info}, so package export is not a second condition here.
     */
    private static void requirePubliclyReachable(Object subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        Class<?> type = subscriber.getClass();
        int modifiers = type.getModifiers();
        // public OR protected: both compile to ACC_PUBLIC, which is what the access check reads.
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
            return;
        }
        throw new IllegalArgumentException(NOT_PUBLIC_PREFIX + type.getName()
                + " is not. The bus invokes reflectively and a non-public class delivers "
                + "nothing, silently — P-10");
    }

    /** Unsubscribes an object; safe to call when it was never registered. */
    public void unregister(Object subscriber) {
        bus.unregister(subscriber);
    }

    public boolean isRegistered(Object subscriber) {
        return bus.isRegistered(subscriber);
    }

    /** Publishes an event to subscribers, on the FX thread. */
    public void post(Object event) {
        poster.run(() -> bus.post(event));
    }

    /** @return the FX-thread seam, for the few legacy screens that await futures directly. */
    public FxThreadPoster poster() {
        return poster;
    }

    /** @return the wrapped greenrobot bus (advanced use: sticky events, priorities). */
    public EventBus bus() {
        return bus;
    }
}
