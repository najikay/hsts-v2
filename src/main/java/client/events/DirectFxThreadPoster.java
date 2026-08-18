package client.events;

/**
 * Synchronous {@link FxThreadPoster}: runs the action on the calling thread.
 *
 * <p>Used by unit tests (assertions can run straight after the post, with no
 * toolkit and no waiting) and as the safe default in any headless context.
 */
public final class DirectFxThreadPoster implements FxThreadPoster {

    @Override
    public void run(Runnable action) {
        action.run();
    }
}
