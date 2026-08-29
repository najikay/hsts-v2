package client.features.bot;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.bot.BotActiveRequest;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotCreateRequest;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotProfile;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSourceRow;
import common.dto.bot.SourceAddRequest;
import common.dto.bot.SourceRemoveRequest;
import common.dto.bot.SourceUpdateRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bot Manager's conversation with the server (E16.12).
 */
class BotManagerSessionTest {

    private static final Instant WHEN = Instant.parse("2026-08-20T10:00:00Z");

    private static final BotManagerPage WITH_BOT = BotManagerPage.of(
            new BotProfile(9L, "22", "Databases 22", "Databases study bot", true),
            List.of(new BotSourceRow(5L, BotSourceKind.PDF, "Week 3 handout",
                    "Dana Cohen", WHEN, 1, 4200)));

    private FakeClientConnection connection;
    private RequestDispatcher dispatcher;
    private BotManagerSession session;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new BotManagerSession(dispatcher, new DirectFxThreadPoster(), "22");
    }

    @Test
    @DisplayName("before the first fetch there is nothing to draw and nothing to claim")
    void startsEmpty() {
        assertThat(session.hasBot()).isFalse();
        assertThat(session.sources()).isEmpty();
        assertThat(session.isLoaded()).isFalse();
        assertThat(session.isBusy()).isFalse();
        assertThat(session.status()).isEmpty();
        assertThat(session.courseCode()).isEqualTo("22");
    }

    @Test
    @DisplayName("a fetch asks for this course and renders what comes back")
    void refresh() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);

        session.refresh().join();

        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.BOT_MANAGER_GET);
        assertThat(((BotCourseRequest) connection.lastSent().getPayload()).courseCode())
                .isEqualTo("22");
        assertThat(session.hasBot()).isTrue();
        assertThat(session.isLoaded()).isTrue();
        assertThat(session.sources()).hasSize(1);
        assertThat(session.source(5L)).isPresent();
        assertThat(session.source(999L)).isEmpty();
    }

    @Test
    @DisplayName("a course with no bot is a page to draw, not an error")
    void noBotIsAPage() {
        connection.replyOk(Verb.BOT_MANAGER_GET, BotManagerPage.none());

        session.refresh().join();

        assertThat(session.isLoaded()).isTrue();
        assertThat(session.hasBot()).isFalse();
        assertThat(session.status()).isEmpty();
    }

    @Test
    @DisplayName("creating sends the name and re-renders from the page it gets back")
    void create() {
        connection.replyOk(Verb.BOT_CREATE, WITH_BOT);

        session.create("Databases study bot").join();

        BotCreateRequest sent = (BotCreateRequest) connection.lastSent().getPayload();
        assertThat(sent.courseCode()).isEqualTo("22");
        assertThat(sent.name()).isEqualTo("Databases study bot");
        assertThat(session.hasBot()).isTrue();
    }

    @Test
    @DisplayName("the toggle sends the state to be in, not an instruction to flip")
    void setActive() {
        connection.replyOk(Verb.BOT_ACTIVE_SET, WITH_BOT);

        session.setActive(false).join();

        BotActiveRequest sent = (BotActiveRequest) connection.lastSent().getPayload();
        assertThat(sent.active()).isFalse();
        assertThat(sent.courseCode()).isEqualTo("22");
    }

    @Test
    @DisplayName("adding a source sends its bytes and its kind")
    void addSource() {
        connection.replyOk(Verb.BOT_SOURCE_ADD, WITH_BOT);

        session.addSource(BotSourceKind.PDF, "Week 3 handout",
                "material".getBytes(StandardCharsets.UTF_8)).join();

        SourceAddRequest sent = (SourceAddRequest) connection.lastSent().getPayload();
        assertThat(sent.kind()).isEqualTo(BotSourceKind.PDF);
        assertThat(sent.title()).isEqualTo("Week 3 handout");
        assertThat(new String(sent.content(), StandardCharsets.UTF_8)).isEqualTo("material");
        assertThat(session.sources()).hasSize(1);
    }

    @Test
    @DisplayName("a parse failure keeps the server's own sentence, which is the useful one")
    void parseFailureKeepsTheServerSentence() {
        connection.replyError(Verb.BOT_SOURCE_ADD, ErrorCode.VALIDATION,
                "This PDF has no text in it. It may be a scan of printed pages.");

        session.addSource(BotSourceKind.PDF, "Scan", new byte[] {1, 2, 3}).join();

        assertThat(session.status()).startsWith("This PDF has no text in it.");
        assertThat(session.isBusy()).isFalse();
    }

    @Test
    @DisplayName("⚑ editing a source names the row it replaces, and keeps it (B-21)")
    void updateSource() {
        BotManagerPage edited = BotManagerPage.of(
                new BotProfile(9L, "22", "Databases 22", "Databases study bot", true),
                List.of(new BotSourceRow(6L, BotSourceKind.TEXT, "Week 3 notes",
                        "Dana Cohen", WHEN, 2, 41, "A foreign key points at a primary key.")));
        connection.replyOk(Verb.BOT_SOURCE_UPDATE, edited);

        session.updateSource(6L, BotSourceKind.TEXT, "Week 3 notes",
                "A foreign key points at a primary key.".getBytes(StandardCharsets.UTF_8)).join();

        SourceUpdateRequest sent = (SourceUpdateRequest) connection.lastSent().getPayload();
        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.BOT_SOURCE_UPDATE);
        assertThat(sent.courseCode()).isEqualTo("22");
        assertThat(sent.sourceId()).isEqualTo(6L);
        assertThat(sent.kind()).isEqualTo(BotSourceKind.TEXT);
        assertThat(sent.title()).isEqualTo("Week 3 notes");
        assertThat(session.sources()).hasSize(1);
        assertThat(session.sources().get(0).version())
                .as("the server's own page comes back, so the bumped version is what is drawn")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a refused edit keeps the server's sentence, holder's name and all (B-21)")
    void updateRefusedByTheLock() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        session.refresh().join();
        connection.replyError(Verb.BOT_SOURCE_UPDATE, ErrorCode.CONFLICT,
                "Avi Mizrahi is editing this source right now. Wait for them to finish, "
                        + "or take over the edit from the banner.");

        session.updateSource(5L, BotSourceKind.TEXT, "Week 3",
                "replacement".getBytes(StandardCharsets.UTF_8)).join();

        assertThat(session.status()).startsWith("Avi Mizrahi is editing this source");
        assertThat(session.sources())
                .as("the page she was looking at survives a refusal")
                .hasSize(1);
    }

    @Test
    @DisplayName("only free-text rows offer an edit, and they carry what to open (B-21)")
    void onlyTextRowsAreEditable() {
        BotSourceRow pdf = new BotSourceRow(5L, BotSourceKind.PDF, "Week 3 handout",
                "Dana Cohen", WHEN, 1, 4200, "should never survive the constructor");
        BotSourceRow typed = new BotSourceRow(6L, BotSourceKind.TEXT, "Week 3 notes",
                "Dana Cohen", WHEN, 1, 41, "A foreign key points at a primary key.");

        assertThat(pdf.isEditable()).isFalse();
        assertThat(pdf.text())
                .as("a file row holds the parse, not the document; the record enforces that")
                .isNull();
        assertThat(typed.isEditable()).isTrue();
        assertThat(typed.text()).isEqualTo("A foreign key points at a primary key.");
    }

    @Test
    @DisplayName("removing a source names the course as well as the row")
    void removeSource() {
        connection.replyOk(Verb.BOT_SOURCE_REMOVE, BotManagerPage.of(
                new BotProfile(9L, "22", "Databases 22", "bot", true), List.of()));

        session.removeSource(5L).join();

        SourceRemoveRequest sent = (SourceRemoveRequest) connection.lastSent().getPayload();
        assertThat(sent.courseCode()).isEqualTo("22");
        assertThat(sent.sourceId()).isEqualTo(5L);
        assertThat(session.sources()).isEmpty();
    }

    @Test
    @DisplayName("a dropped connection is a status line, and the page it had survives")
    void networkFailureKeepsThePage() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        session.refresh().join();

        connection.failSendsWith(new IOException("socket closed"));
        session.refresh().join();

        assertThat(session.status()).isEqualTo(BotCopy.MANAGER_FAILED);
        assertThat(session.sources())
                .as("a failed refresh must not blank a screen that was correct a second ago")
                .hasSize(1);
    }

    @Test
    @DisplayName("an unexpected payload is reported rather than rendered")
    void unexpectedPayload() {
        connection.replyOk(Verb.BOT_MANAGER_GET, "not a page");

        session.refresh().join();

        assertThat(session.status()).isEqualTo(BotCopy.MANAGER_FAILED);
        assertThat(session.isLoaded()).isFalse();
    }

    @Test
    @DisplayName("a refusal with no sentence still says something")
    void refusalWithoutASentence() {
        connection.respondTo(Verb.BOT_SOURCE_REMOVE, request ->
                common.protocol.Message.error(request, ErrorCode.CONFLICT, "  "));

        session.removeSource(5L).join();

        assertThat(session.status()).isEqualTo(BotCopy.MANAGER_FAILED);
    }

    @Test
    @DisplayName("the screen is told when a request starts and when it ends")
    void notifiesOnEveryChange() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        AtomicInteger changes = new AtomicInteger();
        session.onChange(changes::incrementAndGet);

        session.refresh().join();

        assertThat(changes)
                .as("once for busy, once for the answer, so a spinner can appear and go")
                .hasValue(2);
    }

    @Test
    @DisplayName("a view can be handed the page rather than pulling it")
    void onPage() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        AtomicInteger pages = new AtomicInteger();
        session.onPage(page -> pages.incrementAndGet());

        session.refresh().join();

        assertThat(pages).hasValue(2);
        assertThat(session.page()).isEqualTo(WITH_BOT);
    }

    // ===================== The list of bots (U-26) =======================

    /**
     * The manager's master half: one card per taught course (2026-08-29, manual round 3, U-26).
     *
     * <p>The finding these are written against is that a two-course teacher read "one bot" off a
     * screen showing one card. Every assertion here is therefore about a teacher with more than
     * one course, because with one course the old screen was already right and no test that used
     * one could have caught this.
     */
    @Nested
    @DisplayName("⚑ U-26: every taught course, one card each")
    class TheListOfBots {

        private static final CourseRef ALGEBRA = new CourseRef("11", "Algebra 11");
        private static final CourseRef CALCULUS = new CourseRef("12", "Calculus 12");

        private static final BotManagerPage ALGEBRA_PAGE = BotManagerPage.of(
                new BotProfile(1L, "11", "Algebra 11", "Algebra study bot", true),
                List.of(new BotSourceRow(21L, BotSourceKind.PDF, "Week 1 handout",
                                "Dana Cohen", WHEN, 1, 4200),
                        new BotSourceRow(22L, BotSourceKind.TEXT, "Quadratics",
                                "Dana Cohen", WHEN, 1, 90, "b squared minus 4ac.")));

        private static final BotManagerPage CALCULUS_PAGE = BotManagerPage.of(
                new BotProfile(2L, "12", "Calculus 12", "Calculus study bot", false),
                List.of());

        private BotManagerListSession list;

        @BeforeEach
        void twoCourses() {
            list = new BotManagerListSession(dispatcher, new DirectFxThreadPoster(),
                    List.of(ALGEBRA, CALCULUS));
        }

        /** Answers each course with its own page, which is what the server actually does. */
        private void answerWith(Map<String, BotManagerPage> pages) {
            connection.respondTo(Verb.BOT_MANAGER_GET, request -> Message.ok(request,
                    pages.getOrDefault(((BotCourseRequest) request.getPayload()).courseCode(),
                            BotManagerPage.none())));
        }

        @Test
        @DisplayName("the list is one card per taught course, in the order she teaches them")
        void oneCardPerTaughtCourse() {
            answerWith(Map.of("11", ALGEBRA_PAGE, "12", CALCULUS_PAGE));

            list.refreshAll().join();

            assertThat(list.summaries()).extracting(BotCourseSummary::courseCode)
                    .as("both of dana.cohen's courses, not the first one alone")
                    .containsExactly("11", "12");
            assertThat(connection.sentMessages())
                    .as("one read per course; no summary verb, so no wire change")
                    .allMatch(sent -> sent.getVerb() == Verb.BOT_MANAGER_GET)
                    .hasSize(2);

            BotCourseSummary algebra = list.summaries().get(0);
            assertThat(algebra.courseLabel()).isEqualTo("11 · Algebra 11");
            assertThat(algebra.botLabel()).isEqualTo("Algebra study bot");
            assertThat(algebra.stateLabel()).isEqualTo(BotCopy.ACTIVE_CHIP);
            assertThat(algebra.sourcesLabel()).isEqualTo("2 sources");
            assertThat(algebra.actionLabel()).isEqualTo(BotCopy.MANAGE);

            BotCourseSummary calculus = list.summaries().get(1);
            assertThat(calculus.stateLabel())
                    .as("F12.4 is per bot, so the chip is per card")
                    .isEqualTo(BotCopy.INACTIVE_CHIP);
            assertThat(calculus.sourcesLabel()).isEqualTo(BotCopy.SOURCES_EMPTY_TITLE);
        }

        @Test
        @DisplayName("a course with no bot offers Create rather than reporting a void")
        void aCourseWithoutABotOffersCreate() {
            answerWith(Map.of("11", ALGEBRA_PAGE));

            list.refreshAll().join();

            BotCourseSummary calculus = list.summaries().get(1);
            assertThat(calculus.loaded()).isTrue();
            assertThat(calculus.hasBot()).isFalse();
            assertThat(calculus.botLabel()).isEqualTo(BotCopy.NO_BOT_YET);
            assertThat(calculus.actionLabel()).isEqualTo(BotCopy.CREATE_BOT);
            assertThat(calculus.sourcesLabel())
                    .as("no bot, nothing to count; the card offers instead of measuring")
                    .isEmpty();
        }

        @Test
        @DisplayName("before the read lands a card says it is looking, never that there is no bot")
        void anUnreadCourseDoesNotClaimThereIsNoBot() {
            assertThat(list.summaries()).allSatisfy(row -> {
                assertThat(row.loaded()).isFalse();
                assertThat(row.botLabel())
                        .as("telling her she has no bot for a third of a second is how she "
                                + "creates one she already has")
                        .isEqualTo(BotCopy.CARD_LOADING)
                        .isNotEqualTo(BotCopy.NO_BOT_YET);
            });
        }

        @Test
        @DisplayName("selecting a card loads that course's bot, and only that one")
        void selectingACardLoadsThatCoursesBot() {
            answerWith(Map.of("11", ALGEBRA_PAGE, "12", CALCULUS_PAGE));
            list.refreshAll().join();

            assertThat(list.selectedCourse())
                    .as("the first taught course, so a one-course teacher pays no click")
                    .isEqualTo("11");
            assertThat(list.selected().orElseThrow().sources()).hasSize(2);

            assertThat(list.select("12")).isTrue();

            assertThat(list.selectedCourse()).isEqualTo("12");
            assertThat(list.selected().orElseThrow().courseCode()).isEqualTo("12");
            assertThat(list.selected().orElseThrow().page().bot().name())
                    .isEqualTo("Calculus study bot");
            assertThat(list.selected().orElseThrow().sources()).isEmpty();
        }

        @Test
        @DisplayName("the deep link selects the course it names, whatever its case")
        void theDeepLinkSelectsTheRightCard() {
            answerWith(Map.of("11", ALGEBRA_PAGE, "12", CALCULUS_PAGE));
            list.refreshAll().join();

            assertThat(list.select("12")).isTrue();
            assertThat(list.isSelected("12")).isTrue();
            assertThat(list.isSelected("11")).isFalse();

            assertThat(list.select("22"))
                    .as("a course she does not teach is refused, not selected onto a blank pane")
                    .isFalse();
            assertThat(list.selectedCourse())
                    .as("so the screen keeps showing the course it was showing")
                    .isEqualTo("12");
        }

        @Test
        @DisplayName("creating on one course cannot move another course's card (S-30)")
        void creatingOnOneCourseLeavesTheOtherAlone() {
            answerWith(Map.of("11", ALGEBRA_PAGE));
            list.refreshAll().join();
            assertThat(list.summaries().get(1).hasBot()).isFalse();

            connection.respondTo(Verb.BOT_CREATE, request -> Message.ok(request,
                    "12".equals(((BotCreateRequest) request.getPayload()).courseCode())
                            ? CALCULUS_PAGE
                            : BotManagerPage.none()));

            list.sessionFor("12").orElseThrow().create("Calculus study bot").join();

            BotCourseSummary algebra = list.summaries().get(0);
            assertThat(algebra.botLabel())
                    .as("each course has its own session and its own page, so a create "
                            + "addressed to one has nothing shared to reach the other through")
                    .isEqualTo("Algebra study bot");
            assertThat(algebra.sourcesLabel()).isEqualTo("2 sources");
            assertThat(algebra.active()).isTrue();

            BotCourseSummary calculus = list.summaries().get(1);
            assertThat(calculus.hasBot()).isTrue();
            assertThat(calculus.botLabel()).isEqualTo("Calculus study bot");
            assertThat(calculus.actionLabel()).isEqualTo(BotCopy.MANAGE);
        }

        @Test
        @DisplayName("one course refusing leaves the other cards correct")
        void oneCourseFailingDoesNotBlankTheList() {
            connection.respondTo(Verb.BOT_MANAGER_GET, request ->
                    "11".equals(((BotCourseRequest) request.getPayload()).courseCode())
                            ? Message.ok(request, ALGEBRA_PAGE)
                            : Message.error(request, ErrorCode.FORBIDDEN,
                                    "You do not teach this course."));

            list.refreshAll().join();

            assertThat(list.summaries().get(0).botLabel()).isEqualTo("Algebra study bot");
            assertThat(list.sessionFor("12").orElseThrow().status())
                    .as("the refusal is put on the course it belongs to")
                    .isEqualTo("You do not teach this course.");
        }

        @Test
        @DisplayName("the screen is told when any course changes, and when the selection moves")
        void everyChangeReachesTheScreen() {
            answerWith(Map.of("11", ALGEBRA_PAGE, "12", CALCULUS_PAGE));
            AtomicInteger changes = new AtomicInteger();
            list.onChange(changes::incrementAndGet);

            list.refreshAll().join();
            int afterReads = changes.get();
            assertThat(afterReads)
                    .as("two courses, each announcing busy and then its answer")
                    .isEqualTo(4);

            list.select("12");
            assertThat(changes).hasValue(afterReads + 1);
            list.select("12");
            assertThat(changes)
                    .as("re-selecting the course already shown redraws nothing")
                    .hasValue(afterReads + 1);
        }

        @Test
        @DisplayName("a teacher attached to no course gets a state, not a blank column")
        void noCoursesIsAState() {
            BotManagerListSession none = new BotManagerListSession(dispatcher,
                    new DirectFxThreadPoster(), List.of());

            assertThat(none.isEmpty()).isTrue();
            assertThat(none.summaries()).isEmpty();
            assertThat(none.selected()).isEmpty();
            assertThat(none.selectedCourse()).isEmpty();
            none.refreshAll().join();
            assertThat(connection.sentMessages())
                    .as("nothing to read, so nothing is asked")
                    .isEmpty();
        }
    }
}
