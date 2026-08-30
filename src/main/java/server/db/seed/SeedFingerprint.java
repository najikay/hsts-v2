package server.db.seed;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Can {@link SeedMode#LOAD_IF_MISSING} tell "already seeded" from "seeded by an older version
 * of this dataset"? ⚑ (B-24)
 *
 * <h2>The gap this closes, and the one it does not</h2>
 *
 * <p>Idempotency in this loader is decided <b>per row, by natural key</b> — a username, a
 * display id, recipient + type + title — which is what makes {@code LOAD_IF_MISSING} safe to
 * press on a database somebody is using. It also means the mode has no notion of <i>which
 * version</i> of the dataset is in there. Acceptance case 17.2 caught what follows: run
 * against {@code hsts_db}, which still held the pre-translation seed (B-19), one load reported
 * success and inserted 4 bot sources, 4 bot sessions, 4 bot messages and 7 notifications
 * <em>beside</em> eighteen Hebrew-named users. The next run then reported {@code UNCHANGED}, so
 * the hybrid was stable and invisible. On a defence machine that is a mixed-language screen
 * with no warning anywhere.
 *
 * <h2>Why this is a comparison and not a stored marker</h2>
 *
 * <p>The obvious fix is a {@code seed_meta} row holding a dataset version, written by the
 * loader and read back on the next run. <b>There is nowhere to put it without a migration</b>
 * — the schema is twenty tables and none of them is a metadata table, and the alternative
 * (hiding a version string in a content column of a row a user can see) trades one silent
 * problem for a worse one.
 *
 * <p>So the fingerprint is computed from <b>both sides of the comparison</b> instead. The
 * dataset's side is the {@link #PROBES} below: a small, fixed set of facts <i>this build's</i>
 * seed asserts. The database's side is what those same probes actually answer. Two digests
 * over the same ordered list, and a difference means the rows in this database were not put
 * there by this dataset. <b>That is a fingerprint whose storage is the seeded content itself</b>,
 * which is exactly what "no new migration" leaves available.
 *
 * <h2>What it will and will not catch — stated, because a half-honest guard is worse</h2>
 *
 * <ul>
 *   <li><b>Catches</b> the case that actually happened: content that changed while its natural
 *       key did not ({@code dana.cohen} is still {@code dana.cohen}; her display name is no
 *       longer Hebrew), and any table whose row count has moved, in either direction.</li>
 *   <li><b>Catches</b> rows added beside the seed by a person using the app, because the counts
 *       go up. That is a false positive in spirit and a true one in fact: this database is no
 *       longer the dataset, and "reload before you demo" is the right answer either way.</li>
 *   <li><b>Catches an existing demo database that predates U-42 and U-43</b>, which is the case
 *       an operator meets on 2026-08-30: eight of the ten probes moved, so a database seeded
 *       before that session warns on the next {@code LOAD_IF_MISSING} rather than quietly
 *       serving a two-subject school. The answer is the same as always, <b>Reload demo
 *       data</b>: the loader inserts missing rows and cannot retire the ones it no longer
 *       ships.</li>
 *   <li><b>Does not catch</b> a change to seeded content no probe looks at. Ten probes are not
 *       a checksum of 581 rows and this class does not pretend otherwise. It is a spot-check
 *       chosen to be cheap (nine counts and one string), stable across reloads, and pointed at
 *       the things that have actually drifted.</li>
 *   <li><b>Never deletes anything, and never fails a load.</b> It warns. A guard that refused
 *       to load, or that "fixed" the database, would be a far more dangerous thing to run
 *       minutes before a defence than the hybrid it is warning about.</li>
 * </ul>
 *
 * <h2>The duplication, and its tripwire</h2>
 *
 * <p>The expected values below restate numbers that live in {@code SEED_CONTENT.md} and in the
 * sections, which is a second place for them to drift. That is deliberate and it is guarded:
 * {@code SeedDatasetContract.theFingerprintMatchesAFreshlySeededDatabase} loads the real
 * dataset into an empty schema and asserts this class finds no drift, so changing the seed
 * without changing these numbers fails the build rather than warning every operator forever.
 */
final class SeedFingerprint {

    private static final Logger log = LoggerFactory.getLogger(SeedFingerprint.class);

    /** Answer used when a probe finds nothing at all, so "absent" hashes differently from "0". */
    private static final String ABSENT = "<absent>";

    /**
     * One fact this build's dataset asserts about a seeded database.
     *
     * @param what     how the probe reads in a warning, e.g. {@code "users"}
     * @param expected what this dataset says the answer is
     * @param read     what the database says; must never throw
     */
    private record Probe(String what, String expected, Function<Session, String> read) { }

    /**
     * The spot-check. Nine counts and one string, in a fixed order the digest depends on.
     *
     * <p>The counts are the tables whose contents define the demo; the string is
     * {@code dana.cohen}'s display name, which is here because it is the exact field that
     * distinguished the drifted database in 17.2 from a current one — same username, same row,
     * different dataset.
     */
    private static final List<Probe> PROBES = List.of(
            // U-42 (2026-08-30, live session) moved the first three: three subjects, three
            // courses, three teachers, eighteen questions and eighteen question versions.
            count("users", 21),
            count("questions", 58),
            count("question_versions", 61),
            // U-43 moved the rest: the Biology exam is one exam and one version, and its
            // execution plus the second Java one are two sittings, eleven attempts and eleven
            // grades. attempt_answers is not probed, so its 136 -> 203 is not here, and
            // notifications did not move at all - seed §11 says why.
            count("exams", 7),
            count("exam_versions", 8),
            count("exam_executions", 7),
            count("exam_attempts", 31),
            count("grades", 31),
            count("notifications", 10),
            new Probe("dana.cohen's display name", "Dana Cohen", session -> scalar(session,
                    "select u.fullName from User u where u.username = 'dana.cohen'")));

    private SeedFingerprint() {
        // static helper — no instances
    }

    /**
     * What a comparison found.
     *
     * @param expectedDigest this dataset's fingerprint
     * @param actualDigest   the database's
     * @param differences    one line per probe that disagreed, in probe order; empty when the
     *                       two digests match
     */
    record Drift(String expectedDigest, String actualDigest, List<String> differences) {

        Drift {
            differences = List.copyOf(differences);
        }

        /** @return whether this database looks like it was seeded by this build's dataset. */
        boolean isClean() {
            return differences.isEmpty();
        }

        /**
         * The sentence the console result panel and the log both carry.
         *
         * <p>Says what differs and what to do, and claims nothing more than a spot-check can
         * support — "looks like" rather than "is". No em dashes: PRD §4.1, and this is shown
         * in the server console.
         *
         * @return the warning, or {@code ""} when there is nothing to warn about
         */
        String warning() {
            if (isClean()) {
                return "";
            }
            StringBuilder text = new StringBuilder(
                    "WARNING: this database does not look like it was seeded by this build's "
                            + "dataset, so what is in it may be a mix of two versions. "
                            + "Nothing has been deleted. Use Reload demo data before a demo.");
            text.append(String.format("%n  dataset %s, database %s", expectedDigest, actualDigest));
            differences.forEach(line -> text.append(String.format("%n  %s", line)));
            return text.toString();
        }
    }

    /**
     * Compares this build's dataset against what is in the database.
     *
     * @param session an open session, inside the loader's transaction
     * @return what differs, if anything
     */
    static Drift compare(Session session) {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        List<String> differences = new ArrayList<>();

        for (Probe probe : PROBES) {
            String found = safely(probe, session);
            expected.append(probe.what()).append('=').append(probe.expected()).append(';');
            actual.append(probe.what()).append('=').append(found).append(';');
            if (!probe.expected().equals(found)) {
                differences.add(probe.what() + ": this dataset says " + probe.expected()
                        + ", the database says " + found);
            }
        }
        return new Drift(digestOf(expected.toString()), digestOf(actual.toString()), differences);
    }

    /**
     * @return the probe's answer, or {@link #ABSENT} when it could not be read. A probe that
     *         throws must not fail a load — the whole point of this class is that it is
     *         advisory
     */
    private static String safely(Probe probe, Session session) {
        try {
            String found = probe.read().apply(session);
            return found == null ? ABSENT : found;
        } catch (RuntimeException e) {
            log.debug("Fingerprint probe '{}' could not be read", probe.what(), e);
            return ABSENT;
        }
    }

    private static Probe count(String table, int expected) {
        return new Probe(table, Integer.toString(expected), session -> String.valueOf(
                session.createNativeQuery("SELECT COUNT(*) FROM " + table, Long.class)
                        .getSingleResult()));
    }

    private static String scalar(Session session, String hql) {
        List<String> found = session.createQuery(hql, String.class).setMaxResults(1).getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * @param material the probe list rendered as text
     * @return the first eight hex characters of its SHA-256. Short because it is read aloud off
     *         a console rather than compared by machine; the {@link Drift#differences} list is
     *         what actually says what is wrong
     */
    private static String digestOf(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; this is unreachable and is not worth a checked exception
            // travelling up through the loader.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
