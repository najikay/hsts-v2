package client.net;

import common.dto.bank.Question;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RequestDispatcher} (E1.10).
 *
 * <p>The dispatcher is the client's correlation engine, so the contract under
 * test is: the right future completes for the right response (even with many in
 * flight and answers arriving out of order), a silent server eventually fails
 * the future with {@link ErrorCode#TIMEOUT}, pushes reach the listener, and
 * <b>nothing</b> arriving from the network can make {@code dispatchIncoming}
 * throw — it runs on the OCSF read thread, where an exception costs the socket.
 *
 * <p>Timeouts are driven through the package-private {@code TimeoutScheduler}
 * seam so the suite never sleeps waiting for wall-clock time.
 */
class RequestDispatcherTest {

    private FakeClientConnection connection;
    private ManualScheduler scheduler;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        scheduler = new ManualScheduler();
        dispatcher = new RequestDispatcher(connection, Duration.ofSeconds(10), scheduler);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @Test
        @DisplayName("send() writes a REQUEST envelope carrying the verb and payload")
        void writesARequest() {
            Question payload = new Question(1, "q", "a");

            dispatcher.send(Verb.UPDATE_QUESTION, payload);

            Message sent = connection.lastSent();
            assertThat(sent.getVerb()).isEqualTo(Verb.UPDATE_QUESTION);
            assertThat(sent.getStatus()).isEqualTo(Status.REQUEST);
            assertThat(sent.getPayload()).isSameAs(payload);
            assertThat(sent.getRequestId()).isNotBlank();
            assertThat(dispatcher.pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a failed write fails the future immediately instead of waiting for the timeout")
        void failedWriteFailsTheFuture() {
            IOException boom = new IOException("socket closed");
            connection.failSendsWith(boom);

            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);

            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(future::get).hasCause(boom);
            assertThat(dispatcher.pendingCount()).isZero();
            assertThat(scheduler.scheduled()).isZero();
        }

        @Test
        @DisplayName("the constructor refuses null collaborators")
        void constructorValidatesArguments() {
            assertThatNullPointerException().isThrownBy(() -> new RequestDispatcher(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new RequestDispatcher(connection, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new RequestDispatcher(connection, Duration.ofSeconds(1), null));
        }

        @Test
        @DisplayName("the default constructor uses the documented 10s window")
        void defaultTimeoutIsTenSeconds() {
            assertThat(RequestDispatcher.DEFAULT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
            assertThatCode(() -> new RequestDispatcher(connection)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("correlation")
    class Correlation {

        @Test
        @DisplayName("a matching response completes the future and clears the pending entry")
        void matchingResponseCompletes() throws Exception {
            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);
            Message request = connection.lastSent();

            dispatcher.dispatchIncoming(Message.ok(request, List.of(new Question(1, "q", "a"))));

            assertThat(future).isCompleted();
            assertThat(future.get().isOk()).isTrue();
            assertThat(dispatcher.pendingCount()).isZero();
        }

        @Test
        @DisplayName("an ERROR response completes the future normally — the code is data, not an exception")
        void errorResponseCompletesNormally() throws Exception {
            CompletableFuture<Message> future = dispatcher.send(Verb.UPDATE_QUESTION, null);

            dispatcher.dispatchIncoming(
                    Message.error(connection.lastSent(), ErrorCode.VALIDATION, "Answer is required."));

            assertThat(future.get().getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(future.get().errorMessage()).isEqualTo("Answer is required.");
        }

        @Test
        @DisplayName("out-of-order responses each complete their own request")
        void concurrentRequestsAreCorrelatedIndependently() throws Exception {
            List<CompletableFuture<Message>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(dispatcher.send(Verb.GET_ALL_QUESTIONS, "payload-" + i));
            }
            List<Message> requests = new ArrayList<>(connection.sentMessages());
            assertThat(dispatcher.pendingCount()).isEqualTo(5);

            // Answer backwards: index i's answer carries "answer-i".
            Collections.reverse(requests);
            for (Message request : requests) {
                String index = String.valueOf(request.getPayload()).substring("payload-".length());
                dispatcher.dispatchIncoming(Message.ok(request, "answer-" + index));
            }

            for (int i = 0; i < futures.size(); i++) {
                assertThat(futures.get(i).get().getPayload()).isEqualTo("answer-" + i);
            }
            assertThat(dispatcher.pendingCount()).isZero();
        }

        @Test
        @DisplayName("a response for an unknown requestId is dropped, not thrown")
        void unmatchedResponseIsDropped() {
            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);

            assertThatCode(() -> dispatcher.dispatchIncoming(
                    Message.ok(Verb.GET_ALL_QUESTIONS, "some-other-id", List.of())))
                    .doesNotThrowAnyException();

            assertThat(future).isNotDone();
            assertThat(dispatcher.pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a response with no requestId at all is dropped")
        void responseWithoutIdIsDropped() {
            assertThatCode(() -> dispatcher.dispatchIncoming(
                    new Message(Verb.GET_ALL_QUESTIONS, null, Status.OK, null, "orphan")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null message is dropped")
        void nullMessageIsDropped() {
            assertThatCode(() -> dispatcher.dispatchIncoming(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a duplicated response is dropped — a future completes exactly once")
        void duplicateResponseIsDropped() throws Exception {
            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);
            Message request = connection.lastSent();

            dispatcher.dispatchIncoming(Message.ok(request, "first"));
            dispatcher.dispatchIncoming(Message.ok(request, "second"));

            assertThat(future.get().getPayload()).isEqualTo("first");
        }
    }

    @Nested
    @DisplayName("timeouts")
    class Timeouts {

        @Test
        @DisplayName("no answer within the window fails the future with ErrorCode.TIMEOUT")
        void timeoutFailsTheFuture() {
            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);

            scheduler.fireAll();

            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(RequestTimeoutException.class)
                    .hasMessageContaining("GET_ALL_QUESTIONS")
                    .hasMessageContaining("10000 ms");
            assertThat(dispatcher.pendingCount()).isZero();
        }

        @Test
        @DisplayName("the timeout exception carries TIMEOUT, the verb and the request id")
        void timeoutExceptionIsSelfDescribing() {
            CompletableFuture<Message> future = dispatcher.send(Verb.LOGIN, null);
            String requestId = connection.lastSent().getRequestId();

            scheduler.fireAll();

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(future::join);
            assertThat(thrown).isInstanceOf(CompletionException.class);
            RequestTimeoutException failure = (RequestTimeoutException) thrown.getCause();
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(failure.verb()).isEqualTo(Verb.LOGIN);
            assertThat(failure.requestId()).isEqualTo(requestId);
        }

        @Test
        @DisplayName("an answered request ignores its timeout when it later fires")
        void timeoutAfterAnAnswerIsANoOp() throws Exception {
            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);
            dispatcher.dispatchIncoming(Message.ok(connection.lastSent(), "done"));

            scheduler.fireAll();

            assertThat(future.get().getPayload()).isEqualTo("done");
        }

        @Test
        @DisplayName("a late answer after a timeout is dropped, not thrown")
        void lateAnswerIsDropped() {
            CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);
            Message request = connection.lastSent();
            scheduler.fireAll();

            assertThatCode(() -> dispatcher.dispatchIncoming(Message.ok(request, "too late")))
                    .doesNotThrowAnyException();
            assertThat(future).isCompletedExceptionally();
        }

        @Test
        @DisplayName("a per-request timeout overrides the default")
        void perRequestTimeout() {
            dispatcher.send(Verb.LOGIN, null, Duration.ofMillis(250));

            assertThat(scheduler.lastDelay()).isEqualTo(Duration.ofMillis(250));
        }

        @Test
        @DisplayName("the real (unseeded) scheduler also fires — the production path works")
        void realSchedulerFires() {
            RequestDispatcher real = new RequestDispatcher(connection, Duration.ofMillis(30));

            CompletableFuture<Message> future = real.send(Verb.GET_ALL_QUESTIONS, null);

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .cause().isInstanceOf(RequestTimeoutException.class);
        }
    }

    @Nested
    @DisplayName("pushes")
    class Pushes {

        @Test
        @DisplayName("a PUSH message reaches the registered listener and completes no future")
        void pushGoesToTheListener() {
            AtomicReference<Message> received = new AtomicReference<>();
            dispatcher.setPushListener(received::set);
            CompletableFuture<Message> pending = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);

            dispatcher.dispatchIncoming(Message.push(Verb.PUSH_NOTIFICATION, "hello"));

            assertThat(received.get().getVerb()).isEqualTo(Verb.PUSH_NOTIFICATION);
            assertThat(received.get().getPayload()).isEqualTo("hello");
            assertThat(pending).isNotDone();
        }

        @Test
        @DisplayName("a push with no listener registered is dropped")
        void pushWithoutListenerIsDropped() {
            assertThatCode(() -> dispatcher.dispatchIncoming(Message.push(Verb.PUSH_LOCK_CHANGED, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a listener that throws cannot take the read thread down")
        void throwingListenerIsContained() {
            dispatcher.setPushListener(push -> {
                throw new IllegalStateException("subscriber bug");
            });

            assertThatCode(() -> dispatcher.dispatchIncoming(Message.push(Verb.PUSH_NOTIFICATION, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the listener can be cleared again")
        void listenerCanBeCleared() {
            AtomicInteger hits = new AtomicInteger();
            dispatcher.setPushListener(push -> hits.incrementAndGet());
            dispatcher.dispatchIncoming(Message.push(Verb.PUSH_NOTIFICATION, null));

            dispatcher.setPushListener(null);
            dispatcher.dispatchIncoming(Message.push(Verb.PUSH_NOTIFICATION, null));

            assertThat(hits).hasValue(1);
        }
    }

    @Nested
    @DisplayName("connection loss")
    class ConnectionLoss {

        @Test
        @DisplayName("failAllPending fails every in-flight request and reports the count")
        void failAllPendingFailsEveryone() {
            CompletableFuture<Message> first = dispatcher.send(Verb.GET_ALL_QUESTIONS, null);
            CompletableFuture<Message> second = dispatcher.send(Verb.UPDATE_QUESTION, null);
            IOException cause = new IOException("connection reset");

            int failed = dispatcher.failAllPending(cause);

            assertThat(failed).isEqualTo(2);
            assertThat(first).isCompletedExceptionally();
            assertThat(second).isCompletedExceptionally();
            assertThatThrownBy(first::get).hasCause(cause);
            assertThat(dispatcher.pendingCount()).isZero();
        }

        @Test
        @DisplayName("failAllPending on an idle dispatcher is a no-op")
        void failAllPendingWithNothingInFlight() {
            assertThat(dispatcher.failAllPending(new IOException("x"))).isZero();
        }
    }

    @Test
    @DisplayName("thread-safety smoke: 8 threads × 25 requests all correlate correctly")
    void concurrentSendersAllGetTheirOwnAnswer() throws Exception {
        int threads = 8;
        int perThread = 25;
        Map<String, String> answers = new ConcurrentHashMap<>();
        List<CompletableFuture<Message>> futures = Collections.synchronizedList(new ArrayList<>());

        // Every request is answered synchronously, echoing its own payload back.
        connection.respondTo(Verb.GET_ALL_QUESTIONS,
                request -> Message.ok(request, "echo:" + request.getPayload()));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String tag = thread + "-" + i;
                        CompletableFuture<Message> future = dispatcher.send(Verb.GET_ALL_QUESTIONS, tag);
                        futures.add(future);
                        answers.put(tag, String.valueOf(future.get(5, TimeUnit.SECONDS).getPayload()));
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(futures).hasSize(threads * perThread);
        assertThat(answers).hasSize(threads * perThread);
        answers.forEach((tag, answer) -> assertThat(answer).isEqualTo("echo:" + tag));
        assertThat(dispatcher.pendingCount()).isZero();
    }

    /** Captures the dispatcher's timeout tasks so a test can fire them on demand. */
    private static final class ManualScheduler implements RequestDispatcher.TimeoutScheduler {
        private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
        private volatile Duration lastDelay;

        @Override
        public void schedule(Runnable task, Duration delay) {
            lastDelay = delay;
            tasks.add(task);
        }

        void fireAll() {
            List<Runnable> snapshot = List.copyOf(tasks);
            tasks.clear();
            snapshot.forEach(Runnable::run);
        }

        int scheduled() {
            return tasks.size();
        }

        Duration lastDelay() {
            return lastDelay;
        }
    }
}
