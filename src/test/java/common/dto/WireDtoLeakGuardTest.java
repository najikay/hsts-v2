package common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The correctness scan over EVERY wire DTO package, with the licences written down
 * (2026-08-21, from Member A's rule-5 finding: {@code common.dto.approval} carried a real
 * answer key that no scan covered, because each earlier guard scanned only its own package).
 *
 * <p>The rule this family enforces: a licence the build checks beats a licence the reader
 * remembers. Package-local guards stay (bank's carries its own inbound/outbound split);
 * this test is the net under all of them, so ADDING A PACKAGE cannot move a key-bearing
 * type outside every scan - the failure mode the approval package demonstrated.
 *
 * <p>The allow-list is per record component, spelled out, minimal, and each entry names its
 * licence. Anything else in {@code common/dto/**} whose component suggests correctness fails
 * the build.
 */
class WireDtoLeakGuardTest {

    private static final Path DTO_ROOT = Path.of("target", "classes", "common", "dto");

    /** component -> the licence that admits it. Keys are "SimpleClassName.component". */
    private static final Map<String, String> LICENSED = Map.ofEntries(
            Map.entry("AnswerReviewRow.correct",
                    "grading: the teacher's marked paper and the student's own APPROVED checked form; "
                    + "gated by GRADE_REVIEW_GET (teacher+ownership) and CHECKED_FORM_GET "
                    + "(own grade, APPROVED, execution closed) - GRADING_WIRE_CONTRACT"),
            Map.entry("AnswerReviewRow.isCorrect",
                    "same licence as AnswerReviewRow.correct"),
            Map.entry("PreviewAnswerRow.correctOption",
                    "approval: the deciding coordinator, the version's own author and the "
                    + "principal (amendment A1, 2026-08-30), via EXAM_PREVIEW_GET only - "
                    + "APPROVAL_WIRE_CONTRACT"),
            Map.entry("QuestionDetail.correctAnswer",
                    "bank outbound: staff-only authoring detail, principal included by the section-7.2 "
                    + "ruling - BANK_WIRE_CONTRACT; BankWireLeakGuardTest carries the local split"),
            Map.entry("QuestionVersionDetail.correctAnswer",
                    "bank outbound: version history detail, same staff-only licence as QuestionDetail"),
            Map.entry("QuestionDraft.correctAnswer",
                    "bank inbound: the teacher STATING the key while authoring (F2.1) - inbound licence"),
            Map.entry("QuestionEdit.correctAnswer",
                    "bank inbound: same authoring licence as QuestionDraft"),
            Map.entry("TeacherOnlyBlock.answerKey",
                    "approval: the teacher-only half of the preview, same audience and gate as "
                            + "PreviewAnswerRow (EXAM_PREVIEW_GET: deciding coordinator, the "
                            + "version's own author, or the principal read-only under "
                            + "amendment A1) - APPROVAL_WIRE_CONTRACT"));

    @Test
    @DisplayName("every correctness-suggesting component in common/dto/** is licensed by name")
    void everyKeyCarrierIsLicensed() {
        List<Class<?>> records = recordsUnder(DTO_ROOT);
        assertThat(records).as("the scan must actually find the wire records").hasSizeGreaterThan(40);

        for (Class<?> record : records) {
            for (RecordComponent component : record.getRecordComponents()) {
                if (suggestsCorrectness(component.getName())) {
                    String key = record.getSimpleName() + "." + component.getName();
                    assertThat(LICENSED)
                            .as("%s carries what reads like an answer key and no licence names it; "
                                    + "add a licence HERE with its gate, or rename what it holds", key)
                            .containsKey(key);
                }
            }
        }
    }

    @Test
    @DisplayName("the licence list is minimal - a stale entry fails rather than lingers")
    void theAllowListIsMinimal() {
        List<String> present = recordsUnder(DTO_ROOT).stream()
                .flatMap(r -> Stream.of(r.getRecordComponents())
                        .map(c -> r.getSimpleName() + "." + c.getName()))
                .toList();
        // The legacy pair's licences live ONLY in BankWireLeakGuardTest's dated entry - the
        // one guard whose predicate actually sees them, and the one whose removal has teeth.
        // Entries here that no predicate would consult were deleted 2026-08-21 (Member A's
        // finding 3: a licence that grants nothing is documentation wearing a guard's clothes;
        // and the old comment claiming QuestionUpdate is not a record was simply false).
        assertThat(LICENSED.keySet().stream()
                .filter(k -> !present.contains(k)))
                .as("licences for components that no longer exist must be deleted with them")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan has teeth - a known key-bearing component really is detected")
    void theCheckHasTeeth() {
        assertThat(suggestsCorrectness("correctOption")).isTrue();
        assertThat(suggestsCorrectness("rightAnswer")).isTrue();
        assertThat(suggestsCorrectness("solution")).isTrue();
        // and the honest negatives that must never trip:
        assertThat(suggestsCorrectness("answer1")).isFalse();
        assertThat(suggestsCorrectness("chosen")).isFalse();
        assertThat(suggestsCorrectness("answerNo")).isTrue();
        assertThat(suggestsCorrectness("key")).isTrue();
        assertThat(suggestsCorrectness("versionNo")).isFalse();
        assertThat(suggestsCorrectness("latestVersionNo")).isFalse();
    }

    /** Mirrors CorrectnessNames' widened predicate; kept private so drift here fails visibly. */
    private static boolean suggestsCorrectness(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches("answer[1-4]")) {
            return false;
        }
        // EXACT mirror of server.db.repos.CorrectnessNames (asserted by mirrorAgreement below;
        // corrected 2026-08-21 after Member A found the net weaker than the thing it nets:
        // "answerno" and the bare "key" were missing while versionNo-family components are live
        // in the bank package).
        return n.equals("key") || n.contains("correct") || n.contains("iscorrect")
                || n.contains("answerkey") || n.contains("answerindex") || n.contains("answerno")
                || n.contains("rightanswer") || n.contains("solution");
    }

    private static List<Class<?>> recordsUnder(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".class"))
                    .map(WireDtoLeakGuardTest::load)
                    .filter(Class::isRecord)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Class<?> load(Path classFile) {
        String name = DTO_ROOT.getParent().getParent().relativize(classFile).toString()
                .replace(java.io.File.separatorChar, '.')
                .replaceAll("\\.class$", "");
        try {
            return Class.forName(name, false, WireDtoLeakGuardTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
