package common.dto.exam;

import java.io.Serializable;
import java.time.Instant;

/**
 * "This student opened another course's study bot while sitting your exam" (Common tier,
 * E10.7 — C-4, F6.8).
 *
 * <p>C-4's cross-course integrity net. The spec forbids the exam's <em>own</em> course bot
 * during an attempt; it says nothing about another course's, so HSTS allows it, warns the
 * student first that continuing will inform the teacher, and then does exactly that. This
 * record is the half the teacher sees: a flag on the student's row in the live monitor,
 * next to a durable notification.
 *
 * <p>Deliberately factual and undated of judgement. It names a course and a time, because
 * that is all the server knows; whether it means anything is the teacher's call, and the
 * copy on both this flag and its notification is written to leave that call with her.
 *
 * @param courseCode the other course, by code
 * @param courseName the other course's display name, which is what the row shows
 * @param at         when it happened (UTC; the client renders local)
 */
public record IntegrityFlag(String courseCode, String courseName, Instant at) implements Serializable {

    private static final long serialVersionUID = 1L;

    public IntegrityFlag {
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
    }

    /** @return the row's label: "used Java Programming bot". */
    public String label() {
        return "used " + courseName + " bot";
    }
}
