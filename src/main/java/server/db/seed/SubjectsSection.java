package server.db.seed;

import server.db.entities.Course;
import server.db.entities.Subject;

import java.util.List;

/**
 * Seed §1 and §2: the two subjects and four courses (E2.15).
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
            new SeedSubject("10", "מתמטיקה"),
            new SeedSubject("20", "מדעי המחשב"));

    private static final List<SeedCourse> COURSES = List.of(
            new SeedCourse("11", "10", "אלגברה"),
            new SeedCourse("12", "10", "חשבון דיפרנציאלי ואינטגרלי"),
            new SeedCourse("21", "20", "תכנות מונחה עצמים ב-Java"),
            new SeedCourse("22", "20", "בסיסי נתונים"));

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
