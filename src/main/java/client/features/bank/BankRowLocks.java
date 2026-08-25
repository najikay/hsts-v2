package client.features.bank;

import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import common.dto.bank.BankQuestionRow;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LocksSnapshot;
import common.dto.lock.LocksSnapshotRequest;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.features.bank.QuestionLockKey;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who is editing each row the bank list is showing (Presentation tier, E6.14 — F10.3, E18.8).
 *
 * <p>The list's "Editing · Ron Levi" column, and nothing else. It owns one question: for a
 * display id on screen, is somebody in the editor with it right now. {@link BankSession} owns
 * the rows, the filters and the page; this owns the lock dimension over them, so neither class
 * grows the other's concern.
 *
 * <p>Two verbs feed it and they are deliberately different things. {@code LOCK_WATCH} registers
 * interest per row so that later news arrives. {@code LOCKS_SNAPSHOT} is the state at first
 * paint: pushes carry news, not state, so a question locked ten minutes before the screen opened
 * raises nothing and would show as free forever without it.
 *
 * <p><b>The two are sent in that order and the order is load-bearing</b>, which is the part
 * {@code Verb.LOCKS_SNAPSHOT}'s "one snapshot at load plus the pushes afterwards is the complete
 * picture" leaves out: it is complete only if the watch is registered first. See
 * {@link #showing} for the window that opens otherwise.
 *
 * <h2>This class never sends LOCK_RELEASE, and that is a correctness requirement ⚑</h2>
 *
 * <p>{@code Verb.LOCK_WATCH}'s own contract says <em>"to stop watching, send {@code LOCK_RELEASE}
 * for the same entity"</em>. <b>A list screen must not follow that instruction.</b>
 * {@code EditLockService.release} does two jobs in one call: it drops the hold when the caller
 * is the holder, <em>and</em> it unwatches. There is no watcher-only release.
 *
 * <p>The bank list and the question editor key on the identical reference — one numbering scheme
 * by the lead's ruling, applied in {@link QuestionLockKey} — so a release sent from here for a
 * question this same user has open in the editor would drop <b>her own editing lock</b> while
 * she is typing in it, and hand the question to whoever asks next. It is directly reachable:
 * {@code BankView} reaches the editor through {@code navigate()}, so any release-on-hide would
 * race the editor's {@code open()} on the same key.
 *
 * <p>So the watch registrations are never withdrawn. They cost a set entry per row browsed, they
 * are per-socket, and logging out or dropping the connection clears every one of them server
 * side. That is a property of what this class does not do, rather than a rule about ordering
 * that somebody has to keep holding, which is why it is written this way and not fixed with
 * careful sequencing. A watcher-only release is E18's to add if it is ever wanted; until then
 * this class has no code path that could send one, and
 * {@code BankSessionTest.EditingColumn.neverReleasesAnything} is what keeps it that way: it
 * walks a load, a re-page, every filter, a selection, a failed page and leaving the screen, and
 * fails if the verb appears on any of them.
 *
 * <h2>The key is only ever mapped forwards</h2>
 *
 * <p>A display id becomes an {@link EntityRef} through {@link QuestionLockKey#of}, never back
 * again. Reversing would mean formatting a {@code long} to five digits, and a course code may
 * lead with a zero — {@code 01003} keys {@code question#1003} — so the reverse is lossy in
 * exactly the case that looks fine in testing. Instead {@link #showing} holds the forward map
 * for the rows on screen, and an answer or a push is matched by looking its reference up in it.
 * A reference that is not in the map is not on screen, which is the same answer this class owes
 * for a push about somebody else's page.
 *
 * <p>A row whose id does not key is <b>logged and skipped</b>, not rethrown. It is a server
 * defect - {@code display_id} is five digits by schema and unique (S-8) - and the editor, which
 * makes the same call for a single entity the user explicitly opened, does let it throw. A list
 * cannot: every path into this class runs inside the dispatcher's {@code whenComplete}, which
 * captures a throwable into an unobserved future, so a throw here would stop
 * {@code BankSession.settleList} before its own {@code onChange.run()} and leave the teacher on
 * the loading spinner permanently, with no error and nothing to retry. One malformed id would
 * take the whole browse screen down. An ERROR naming the row is the loudest thing available
 * that does not do that.
 *
 * <p>FX-free, so every state is a unit test against {@code FakeClientConnection}.
 *
 * <h2>Public because the bus reaches it by reflection, not because anything outside calls it ⚑</h2>
 *
 * <p>Every member below is package-private except {@link #onServerPush}, which the bus itself
 * calls, and nothing outside {@link BankSession} constructs or reads one. The <b>class</b> is
 * public for one reason: greenrobot's EventBus invokes
 * {@link #onServerPush} reflectively from its own package, and a public method on a
 * package-private class is not accessible that way. A package-private subscriber registers
 * without complaint, then throws {@code IllegalAccessException} on the first push, which
 * {@code RequestDispatcher.deliverPush} catches and logs so that a broken subscriber cannot take
 * the socket down. <b>The screen is then simply never updated and nothing fails.</b> Measured
 * here, not reasoned about: this class was package-private first and the push tests were what
 * found it. Do not narrow it back.
 */
public final class BankRowLocks {

    private static final Logger log = LoggerFactory.getLogger(BankRowLocks.class);

    private final RequestDispatcher dispatcher;
    private final ClientEventBus eventBus;
    private final FxThreadPoster poster;
    private final long selfUserId;

    private Runnable onChange = () -> { };

    /** The rows on screen, forward-mapped: reference to the display id that produced it. */
    private Map<EntityRef, String> showing = Map.of();

    /** Display id to whoever holds it. Only live holds are in here; free rows are absent. */
    private Map<String, LockHolder> holders = new LinkedHashMap<>();

    /** Everything watched on this socket. Never shrinks: see the class javadoc. */
    private final Set<EntityRef> watched = new HashSet<>();

    /**
     * The list generation the rows on screen belong to.
     *
     * <p>Owned by {@link BankSession} and passed in, never counted here. The staleness rule is
     * the one that class already documents at {@code listGeneration}, and a second counter would
     * be a second expression of it that agrees until the day it does not.
     */
    private int shownGeneration = -1;

    private boolean started;

    /**
     * @param dispatcher the request correlator
     * @param eventBus   the app bus; {@code PUSH_LOCK_CHANGED} arrives on it already on the FX
     *                   thread
     * @param poster     the FX-thread hop for dispatcher answers, which do not arrive on it
     * @param selfUserId this client's user id, so a row this user is editing can say so
     */
    BankRowLocks(RequestDispatcher dispatcher, ClientEventBus eventBus, FxThreadPoster poster,
                 long selfUserId) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.selfUserId = selfUserId;
    }

    /** Registers the "re-read me and re-render" callback. */
    BankRowLocks onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Lifecycle =====================================

    /** Subscribes to lock pushes. Idempotent, so a screen shown twice does not double-subscribe. */
    void start() {
        if (!started) {
            eventBus.register(this);
            started = true;
        }
    }

    /**
     * Unsubscribes and forgets what was on screen.
     *
     * <p><b>Withdraws no watch</b>, for the reason in the class javadoc. What is dropped is this
     * client's interest in rendering them, which is all a screen being left needs to drop.
     */
    void stop() {
        if (started) {
            eventBus.unregister(this);
            started = false;
        }
        showing = Map.of();
        holders = new LinkedHashMap<>();
        shownGeneration = -1;
        // Cleared with the rest, because this set is a cache of what the SERVER is holding and
        // the server drops every registration on a socket drop (EditLockService.detach ->
        // forgetWatcher), without telling anyone. Keeping the set across that leaves this class
        // believing it is watching rows nobody is watching: watchUnwatched would send nothing,
        // the first snapshot would still paint correctly, and the column would then never
        // update again for the rest of the session. Re-watching is idempotent and raises no
        // push, so the cost of clearing it is one message per row per visit and the cost of not
        // clearing it is a feature that silently stops working after a reconnect.
        watched.clear();
    }

    // ===================== The rows on screen ============================

    /**
     * Adopts the page that just landed: asks who holds these rows, and watches the ones not
     * already watched.
     *
     * @param rows       the page now on screen
     * @param generation the list generation those rows arrived under, from {@link BankSession}
     */
    void showing(List<BankQuestionRow> rows, int generation) {
        Objects.requireNonNull(rows, "rows");
        shownGeneration = generation;
        holders = new LinkedHashMap<>();

        Map<EntityRef, String> next = new LinkedHashMap<>();
        for (BankQuestionRow row : rows) {
            try {
                next.put(QuestionLockKey.of(row.displayId5()), row.displayId5());
            } catch (IllegalArgumentException notADisplayId) {
                // Logged and skipped, NOT rethrown, and the difference is the whole screen.
                // This runs inside settleList, which runs inside the dispatcher's whenComplete:
                // a throw here is captured into an unobserved future, so settleList's own
                // onChange.run() never fires and the teacher is left on the LOADING spinner
                // forever, with no error and nothing in the UI to retry. One malformed id out
                // of a page of forty would take the whole bank down that way.
                //
                // It is still a server defect - display_id is five digits by schema and unique
                // (S-8) - so it is an ERROR in the log naming the row. What it must not be is
                // the browse screen's failure.
                log.error("Bank row '{}' is not a five-digit display id, so its Editing chip "
                                + "cannot be keyed (S-8). The rest of the page is unaffected.",
                        row.displayId5(), notADisplayId);
            }
        }
        // Insertion-ordered, not Map.copyOf: the ids travel in the order the rows are painted,
        // so the truncation in requestSnapshot drops the LAST rows of a too-long page rather
        // than an arbitrary subset, which is what its log line claims and what a reader
        // scrolling from the top would expect.
        showing = Collections.unmodifiableMap(next);

        if (showing.isEmpty()) {
            // An empty page asks nothing. LocksSnapshotRequest would accept the empty list, but
            // a round trip whose answer cannot say anything is a round trip not worth making.
            onChange.run();
            return;
        }
        // WATCH FIRST, THEN THE SNAPSHOT. The order is the correctness, not a preference ⚑
        //
        // The server's publish() resolves recipients by reading its watcher set at the moment
        // the lock changes (EditLockService.publish). So with the snapshot asked for first,
        // there is a window between the server answering "q11005 is free" and the server
        // handling this client's LOCK_WATCH, and a colleague who acquires inside that window
        // publishes to a set this client is not yet in. No push is ever written, the snapshot
        // already said free, and the row shows free for the entire time he is editing it -
        // which is the one case this column exists to prevent.
        //
        // Watching first has no matching hazard. Both messages travel one ordered socket and
        // cross to the FX thread through the same poster queue, so an acquire in the window
        // publishes a push that is written BEFORE the snapshot answer, and the snapshot - which
        // is the newer truth and already contains the acquire - lands last and wins.
        watchUnwatched();
        requestSnapshot(generation);
    }

    private void requestSnapshot(int generation) {
        List<Long> all = showing.keySet().stream().map(EntityRef::entityId).toList();
        // The bank contract clamps a page well under MAX_IDS, so the truncation below is
        // unreachable today. It is here for the day somebody raises the page size and not the
        // cap: an oversized request comes back a refusal, which would take the column out on
        // large pages entirely rather than on the rows past the cap.
        //
        // NOT a thrown exception, which was the first version of this and did not work. Every
        // path into here runs inside the dispatcher's whenComplete, which captures a throwable
        // into the future instead of propagating it, so the "loud failure" would have been a
        // silently dead page render. Logged and truncated is the loudest thing that is true.
        List<Long> ids = all;
        if (all.size() > LocksSnapshotRequest.MAX_IDS) {
            ids = all.subList(0, LocksSnapshotRequest.MAX_IDS);
            log.error("A bank page carried {} rows but one locks snapshot may ask about {}. "
                            + "The Editing column will be blank past row {}. Raise the cap or "
                            + "lower the page size; do not leave them disagreeing.",
                    all.size(), LocksSnapshotRequest.MAX_IDS, LocksSnapshotRequest.MAX_IDS);
        }
        LocksSnapshotRequest request = LocksSnapshotRequest.of(EntityRef.QUESTION, ids);
        dispatcher.send(Verb.LOCKS_SNAPSHOT, request)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleSnapshot(generation, response, failure)));
    }

    private void settleSnapshot(int generation, Message response, Throwable failure) {
        if (generation != shownGeneration) {
            // The page moved while this was in flight, so these holders describe an older
            // moment. Note what this catches that the forward map does not: a row the two pages
            // have in COMMON is still in `showing`, so without this check an older answer would
            // overwrite a newer one and park a colleague's name on a question he has closed.
            // The rows that merely paged away are refused by the map either way.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof LocksSnapshot payload)) {
            // Deliberately silent. Nobody being shown as editing is the truthful reading of "we
            // could not find out", and the alternative is an error banner over a browse screen
            // that is working, about a column that decorates it.
            return;
        }
        Map<String, LockHolder> next = new LinkedHashMap<>();
        payload.holders().forEach((entityId, holder) -> {
            String displayId = showing.get(new EntityRef(EntityRef.QUESTION, entityId));
            if (displayId != null && holder != null) {
                next.put(displayId, holder);
            }
        });
        holders = next;
        onChange.run();
    }

    private void watchUnwatched() {
        for (EntityRef entity : showing.keySet()) {
            if (watched.add(entity)) {
                // Fire and forget. The answer is a LockResponse describing the entity, which the
                // snapshot above already covers for every row on screen; what this call is for
                // is the registration, and that is done by the send.
                dispatcher.send(Verb.LOCK_WATCH, new LockRequest(entity));
            }
        }
    }

    // ===================== Pushes ========================================

    /**
     * Applies a lock change for a row on screen (E18.8). Everything else on the bus, including a
     * lock change for a question on another page, passes through.
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_LOCK_CHANGED) {
            return;
        }
        if (!(event.payload() instanceof LockChange change)) {
            return;
        }
        String displayId = showing.get(change.entity());
        if (displayId == null) {
            return;
        }
        Map<String, LockHolder> next = new LinkedHashMap<>(holders);
        if (change.kind() == LockChange.Kind.ACQUIRED && change.holder() != null) {
            next.put(displayId, change.holder());
        } else {
            // RELEASED and EXPIRED both mean the row is free now. An ACQUIRED with no holder
            // would be a malformed push and is treated as free rather than trusted.
            next.remove(displayId);
        }
        holders = next;
        onChange.run();
    }

    // ===================== Reading it ====================================

    /**
     * @param displayId5 a row's five-digit id
     * @return who is editing it, or empty when nobody is
     */
    Optional<LockHolder> holderOf(String displayId5) {
        return Optional.ofNullable(holders.get(displayId5));
    }

    /**
     * @param holder a holder from {@link #holderOf}
     * @return whether that holder is this client's user, which the column words differently
     */
    boolean isSelf(LockHolder holder) {
        return holder != null && holder.is(selfUserId);
    }
}
