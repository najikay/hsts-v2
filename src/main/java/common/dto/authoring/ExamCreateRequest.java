package common.dto.authoring;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code EXAM_CREATE} payload: a whole exam, composed, in one message (Common tier, E7.1 —
 * F3.1, F3.4, S-10).
 *
 * <h2>It carries the whole composition, and that is structural</h2>
 *
 * <p>The contract's section 1 rule is that an exam version which exists is a releasable object
 * or an unsubmitted {@code DRAFT}, with no third state, because {@code sum(points) = 100} cannot
 * be a DDL constraint and every downstream tier assumes it anyway. Creating an empty exam and
 * filling it in later would put an unsatisfiable row in {@code exam_versions} for as long as the
 * teacher is thinking — and T-3.5 says in plain words that a refused auto-composition creates
 * <b>no exam</b>, which an empty draft sitting in her drawer would falsify. So there is no
 * work-in-progress row: a half-composed exam lives in the client and nowhere else, which is what
 * F3.1's "save blocked (not warned)" already required.
 *
 * <p><b>No display id.</b> The server allocates the 6-digit serial (S-10,
 * {@code ExamIdAllocator}), which is why creating cannot be expressed as an edit of an exam that
 * does not exist yet. <b>No author id</b> either: authorship is {@code CallerContext.userId()}
 * (S-12), so an exam cannot be created in somebody else's name (P-5).
 *
 * <h2>What is normalised here, and what is refused elsewhere</h2>
 *
 * <p>The compact constructor <b>normalises and never throws</b>. {@code courseCode} is
 * {@code strip()}ped — never {@code trim()}ped, the house rule imported verbatim from the bank
 * contract, because {@code trim} cuts only characters at or below U+0020 and a code carrying a
 * Unicode space above it would match the row in SQL ({@code courses.code2} is {@code CHAR(2)}
 * under a PAD SPACE collation) while failing Java equality against the reachable set.
 * {@code name} is stripped without folding to {@code null}, because it is required and the
 * validator's sentence should name a blank name rather than a missing one. The two optional
 * texts are stripped and then folded blank-to-{@code null}, so a request built from an untouched
 * text area and one built from a text area holding two spaces are the same request.
 *
 * <p>Per the package javadoc, every <em>rule</em> is {@code ExamValidator}'s, shared by create
 * and save: a non-blank {@code name} of at most {@link #MAX_NAME_LENGTH} characters,
 * {@code durationMinutes} in {@link #MIN_DURATION_MINUTES}..{@link #MAX_DURATION_MINUTES}, each
 * text at most {@link #MAX_TEXT_LENGTH} characters, at least one question, points summing to
 * exactly {@link #POINTS_TOTAL}, and section 5.2's composition rules. Each failure is a
 * {@code VALIDATION} answer naming the offending field. None of it is an
 * {@link IllegalArgumentException} thrown inside a deserialization on a socket read thread,
 * which would turn a teacher's typo into a dropped connection (E1.11).
 *
 * @param courseCode      the course to file the exam under; {@code requireTeachesCourse} throws
 *                        {@code FORBIDDEN} here, because a refusal naming a course she already
 *                        named tells her nothing she did not know
 * @param name            what the exam is called; stripped, never {@code null}-folded
 * @param durationMinutes how long students get, {@link #MIN_DURATION_MINUTES}..{@link
 *                        #MAX_DURATION_MINUTES} (checked by the handler)
 * @param studentText     the instructions printed on the paper, or {@code null}; blank is
 *                        {@code null}
 * @param teacherText     the notes only staff ever read, or {@code null}; blank is {@code null}
 * @param questions       the composition in paper order, {@code ord} being the index; never
 *                        {@code null} after construction, tolerantly copied
 */
public record ExamCreateRequest(String courseCode,
                                String name,
                                int durationMinutes,
                                String studentText,
                                String teacherText,
                                List<QuestionPin> questions) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Longest exam name the handler accepts ({@code name VARCHAR(150) NOT NULL}). */
    public static final int MAX_NAME_LENGTH = 150;

    /** Shortest exam, in minutes ({@code ck_exam_versions_duration} gives {@code > 0}). */
    public static final int MIN_DURATION_MINUTES = 1;

    /**
     * Longest exam, in minutes: eight hours (lead's ruling 3 of 2026-08-23).
     *
     * <p>Invented by the contract rather than read off the column, which forbids only zero and
     * negatives. The draft proposed 600 and the ruling cut it to 480, for the reason the ceiling
     * exists at all: it is there to catch a typo of {@code 600} for {@code 60}, and a ceiling of
     * 600 admits that exact typo. Eight hours is already far past any real exam and an exam
     * whose timer says ten hours is a live execution nobody can end.
     */
    public static final int MAX_DURATION_MINUTES = 480;

    /**
     * Longest student or teacher text, in characters.
     *
     * <p>Deliberately far below what MySQL {@code TEXT} holds (65,535 <b>bytes</b>, and utf8mb4
     * spends up to 4 per character, so 4000 characters always fits). The point is not storage:
     * a pasted textbook chapter renders as an exam form nobody can read, and the refusal has to
     * arrive as a sentence rather than as a truncation nobody notices until a student is sitting
     * the exam.
     */
    public static final int MAX_TEXT_LENGTH = 4000;

    /**
     * What the points of a composition must sum to. Exactly: not at least, not approximately
     * (F3.1, S-11).
     *
     * <p>This is the number contract section 1 is arranged around. It cannot be a DDL
     * constraint — a table-level {@code CHECK} cannot span rows, and {@code V3__exams.sql} says
     * so in a comment — so it is enforced on the write path with no exceptions, and the constant
     * lives on the wire so the live Σ/100 indicator the teacher watches (E7.3, T-3.2) and the
     * server that refuses her save are counting to the same number.
     */
    public static final int POINTS_TOTAL = 100;

    /** Normalises text and takes a tolerant copy of the composition; never throws. */
    public ExamCreateRequest {
        courseCode = strip(courseCode);
        name = strip(name);
        studentText = blankToNull(studentText);
        teacherText = blankToNull(teacherText);
        questions = tolerantCopy(questions);
    }

    /** @return {@code true} when instructions for the students travel with this exam. */
    public boolean hasStudentText() {
        return studentText != null;
    }

    /** @return {@code true} when staff-only notes travel with this exam. */
    public boolean hasTeacherText() {
        return teacherText != null;
    }

    /**
     * @param raw text as typed, or {@code null}
     * @return it stripped, or {@code null} when it was {@code null}. {@code strip} rather than
     *         {@code trim}: the house rule, and the one that reaches the Unicode spaces above
     *         U+0020 that a PAD SPACE {@code CHAR(2)} column would happily match on
     *
     * <p><b>How far it gets, measured rather than assumed</b>, on the same footing
     * {@code BankBrowseService} states it: {@code String.strip()} removes what
     * {@link Character#isWhitespace(char)} accepts, and the non-breaking spaces U+00A0, U+2007
     * and U+202F are exactly the ones that predicate rejects. A course code padded with one of
     * those arrives at the guard unchanged and is refused, because it equals no member of the
     * reachable set. That fails <b>closed</b>, which is the safe direction; the dangerous one
     * would be a value SQL matches while the guard does not. Widening to a full Unicode-space
     * fold changes what a course code is allowed to be, which is a lead decision rather than a
     * DTO's.
     */
    static String strip(String raw) {
        return raw == null ? null : raw.strip();
    }

    /**
     * @param raw text as typed, or {@code null}
     * @return it stripped, or {@code null} when there was nothing but whitespace there, so that
     *         "she wrote nothing" has exactly one representation on the wire
     */
    static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * The inbound list copy: immutable, and tolerant of both a {@code null} list and a
     * {@code null} element.
     *
     * <p><b>Not {@link List#copyOf}</b>, which throws on a null element. This constructor runs
     * on the server's socket read thread during deserialization, where any throw kills the
     * connection (E1.11, found by Member A on 2026-08-21). A null element must survive
     * construction so that {@code ExamValidator} can refuse it with a named {@code VALIDATION}
     * sentence instead of the teacher losing her composition to a silent disconnect.
     *
     * @param pins the composition as it arrived, possibly {@code null}
     * @return an unmodifiable copy, empty when {@code null} arrived
     */
    static List<QuestionPin> tolerantCopy(List<QuestionPin> pins) {
        return pins == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(pins));
    }
}
