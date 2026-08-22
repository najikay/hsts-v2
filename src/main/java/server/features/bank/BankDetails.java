package server.features.bank;

import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionVersionDetail;
import org.hibernate.Session;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.repos.CourseRepository;
import server.db.repos.UserRepository;

import java.util.List;
import java.util.Objects;

/**
 * Stored rows to the wire's question shapes, in one place for both halves of E6.
 *
 * <h2>Why this is a class and not a method on each service</h2>
 *
 * <p>{@code QuestionDetail} is the OK payload of <b>four</b> verbs: {@code QUESTION_CREATE},
 * {@code QUESTION_UPDATE} and {@code QUESTION_GET} all answer with one, and
 * {@code QUESTION_VERSIONS} answers with a list of the version half. Writes are
 * {@link QuestionService}'s and reads are {@link BankBrowseService}'s, so a mapper per service
 * would be two expressions of one rule with nothing checking them against each other. That is
 * the hazard the wire contract's section 5 keeps having to close, and here it would surface as a
 * teacher seeing a different author name, or a different course label, depending on whether she
 * had just saved the question or merely opened it.
 *
 * <p>So both services hold one of these and neither owns the mapping.
 *
 * <h2>Missing names degrade, they do not throw</h2>
 *
 * <p>A course whose row has no name falls back to its code, and an author who is no longer in
 * {@code users} maps to an empty string. Both are cases where the question itself is intact and
 * only its labelling is not: refusing the whole read would take a working bank away from a
 * teacher over a cosmetic gap, and E9's archived accounts make the second case reachable rather
 * than theoretical.
 */
final class BankDetails {

    private final CourseRepository courses;
    private final UserRepository users;

    BankDetails(CourseRepository courses, UserRepository users) {
        this.courses = Objects.requireNonNull(courses, "courses");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * One question at one version, as the editor and the detail pane read it.
     *
     * @param session         the open session
     * @param question        the question row
     * @param version         the version to render
     * @param latestVersionNo the highest version number the question has, so the client can tell
     *                        an old version from the current one without a second round trip
     * @return the wire detail, correct answer included
     */
    QuestionDetail detail(Session session, Question question, QuestionVersion version,
                          int latestVersionNo) {
        return new QuestionDetail(
                question.getDisplayId(),
                question.getCourseCode(),
                courseName(session, question.getCourseCode()),
                version.getVersionNo(),
                latestVersionNo,
                version.getText(),
                answersOf(version),
                version.getCorrectAnswer(),
                version.getTopic(),
                wireDifficulty(version.getDifficulty()),
                version.hasImage(),
                authorName(session, version.getCreatedBy()),
                version.getCreatedAt());
    }

    /**
     * One row of the version history timeline (E6.12).
     *
     * <p>Carries {@code hasImage} rather than the bytes, for the reason the entity's javadoc
     * gives: a history panel of ten versions would otherwise move up to 20MB to draw a list of
     * dates. E6.6's {@code QUESTION_IMAGE_GET} fetches one on demand.
     *
     * @param session the open session
     * @param version the version to render
     * @return the wire version detail, correct answer included
     */
    QuestionVersionDetail versionDetail(Session session, QuestionVersion version) {
        return new QuestionVersionDetail(
                version.getVersionNo(),
                version.getText(),
                answersOf(version),
                version.getCorrectAnswer(),
                version.getTopic(),
                wireDifficulty(version.getDifficulty()),
                version.hasImage(),
                authorName(session, version.getCreatedBy()),
                version.getCreatedAt());
    }

    /**
     * The four answers in stored order.
     *
     * <p>Positional and it has to be: {@code correctAnswer} is a 1..4 index into exactly this
     * order (C-8 / ADR-016), so reordering here would silently repoint the answer key.
     */
    private static List<String> answersOf(QuestionVersion version) {
        return List.of(version.getA1(), version.getA2(), version.getA3(), version.getA4());
    }

    private String courseName(Session session, String courseCode) {
        return courses.findName(session, courseCode).orElse(courseCode);
    }

    private String authorName(Session session, long userId) {
        return users.findById(session, userId).map(user -> user.getFullName()).orElse("");
    }

    /**
     * Stored difficulty to wire difficulty.
     *
     * <p>{@code valueOf} over the name rather than a switch, because {@code BankDtoTest} asserts
     * the two enums are member-for-member identical, so a member added to one and not the other
     * fails a test rather than falling through a default nobody reads.
     */
    private static common.dto.bank.Difficulty wireDifficulty(
            server.db.entities.Difficulty difficulty) {
        return common.dto.bank.Difficulty.valueOf(difficulty.name());
    }
}
