package server.db.seed;

import server.db.entities.Course;
import server.db.entities.Subject;

import java.util.List;

/**
 * Seed §1 and §2: the five subjects and seven courses (E2.15).
 *
 * <p><b>⚑ U-42 (2026-08-30, live session): Biology 30, Chemistry 40 and Physics 50, one course
 * each.</b> The dataset had two subjects and four courses, which is enough to prove every rule
 * and not enough to look like a school - a subject picker with two entries reads as a fixture,
 * and the reports screen compares across a list the principal can exhaust at a glance. The
 * {@code code2} convention is the subject's first digit plus a serial within it (ARCHITECTURE
 * §5), so 30 holds 31, 40 holds 41 and 50 holds 51. The three new subjects hold <em>one</em>
 * course each, which the convention allows and the original four do not happen to show.
 *
 * <p>Both are read-only reference data in the product (S-3, "subjects and courses come from an
 * external system"), so the seed is the only thing that ever creates them.
 *
 * <p>Keyed on {@code code2}, which is the primary key here rather than a surrogate, so these
 * two are the only sections whose natural key and database key are the same thing.
 */
final class SubjectsSection implements SeedSection {

    private record SeedSubject(String code, String name) { }

    private record SeedCourse(String code, String subject, String name) { }

    private static final List<SeedSubject> SUBJECTS = List.of(
            new SeedSubject("10", "Mathematics"),
            new SeedSubject("20", "Computer science"),
            // ⚑ U-42, seed §1.
            new SeedSubject("30", "Biology"),
            new SeedSubject("40", "Chemistry"),
            new SeedSubject("50", "Physics"));

    private static final List<SeedCourse> COURSES = List.of(
            new SeedCourse("11", "10", "Algebra"),
            new SeedCourse("12", "10", "Calculus"),
            new SeedCourse("21", "20", "Object oriented programming in Java"),
            new SeedCourse("22", "20", "Databases"),
            // ⚑ U-42, seed §2. One course per new subject.
            new SeedCourse("31", "30", "Biology"),
            new SeedCourse("41", "40", "Chemistry"),
            new SeedCourse("51", "50", "Physics"));

    @Override
    public String name() {
        return "1-2 subjects and courses";
    }

    @Override
    public void load(SeedContext context) {
        int subjects = 0;
        for (SeedSubject subject : SUBJECTS) {
            if (!SeedLookup.subjectExists(context.session(), subject.code())) {
                context.session().persist(new Subject(subject.code(), subject.name()));
                subjects++;
            }
        }
        context.recordInserts("subjects", subjects);

        int courses = 0;
        for (SeedCourse course : COURSES) {
            if (!SeedLookup.courseExists(context.session(), course.code())) {
                context.session().persist(
                        new Course(course.code(), course.subject(), course.name()));
                courses++;
            }
        }
        context.recordInserts("courses", courses);
    }
}
