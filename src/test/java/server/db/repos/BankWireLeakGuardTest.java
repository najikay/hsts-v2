package server.db.repos;

import common.dto.bank.BankQuestionRow;
import common.dto.bank.Question;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.dto.bank.QuestionUpdate;
import common.dto.bank.QuestionVersionDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E2.12 guarantee carried onto the <b>authoring</b> wire (E6 — F2.1).
 *
 * <h2>Why a third guard, and why it is the hardest of the three</h2>
 *
 * <p>{@link CorrectnessLeakGuardTest} keeps the answer key inside the database on
 * student-facing reads. {@link ExamWireLeakGuardTest} keeps it off the types a student
 * receives while sitting a paper. Both enforce an absolute: <em>no key here, ever</em>, which
 * is easy to state and easy to check.
 *
 * <p>{@code common.dto.bank} cannot make that claim. The recipient is a teacher editing a
 * question, and an editor that cannot show which answer is right cannot be used to author, so
 * the key <b>must</b> travel on this wire. The property is the weaker "no key on a path a
 * student can reach" — and a weaker claim needs a stronger guard, because "some types here
 * carry a key" is exactly the sentence under which one more would never be noticed.
 *
 * <p>So this guard is an allow-list rather than a prohibition. Four types are licensed by name,
 * each with the reason written down; anything else in the package with a correctness-suggesting
 * component fails the build the moment it is written.
 *
 * <h2>The allow-list is split by direction, and the split is load-bearing</h2>
 *
 * <p>{@link #INBOUND_LICENCE} and {@link #OUTBOUND_LICENCE} are two lists rather than one
 * because they are two different arguments. {@code QuestionDraft} and {@code QuestionEdit} trip
 * the shared predicate for an uninteresting reason: the teacher is <em>submitting</em> the key
 * and the server already holds every key in the bank, so there is nothing to leak. Only the
 * outbound pair is a licence in the sense the take-exam guard means the word.
 *
 * <p>Asserting the split separately is what stops the honest failure mode: a future outbound
 * DTO being waved onto a single flat list by pointing at the two inbound entries already on it.
 *
 * <h2>What this guard does NOT cover, stated rather than discovered</h2>
 *
 * <p>{@link #LEGACY_NOT_COVERED}. The prototype's {@link Question} carries a real answer key in
 * a field called {@code answer}, and the scan is blind to it twice over: {@code answer} matches
 * nothing in {@link CorrectnessNames} — deliberately, because {@code answer1..answer4} are the
 * options a student is meant to see — and {@code Question} is a mutable class rather than a
 * record. Retiring the pair is a scheduled PR after E6 merges. Until then the exclusion is
 * named, dated and tested, so a reader of this file learns the limit here instead of finding it
 * later.
 *
 * <h2>Name-based, and that is enough</h2>
 *
 * <p>Same reasoning as the take-exam guard: a determined author could still smuggle correctness
 * through a component called {@code hint}. The defence that matters is making the honest version
 * of the mistake impossible and the dishonest version visible in a diff.
 */
class BankWireLeakGuardTest {

    private static final String PACKAGE = "common.dto.bank";

    private static final Path COMPILED_BANK_DTOS =
            Path.of("target", "classes", "common", "dto", "bank");

    /**
     * Key-bearing records the teacher <b>sends</b>, licensed because there is nothing to leak.
     *
     * <p>The teacher is submitting the answer key she just typed. The server already knows every
     * key in the bank, so a key travelling towards it reveals nothing to anybody. These two are
     * on the list only because {@link CorrectnessNames} matches any name containing
     * {@code correct} and would otherwise red-line the package the day it was created — and the
     * tempting fix for that, widening the shared predicate, would silently weaken the other two
     * guards.
     *
     * <p><b>These are not the interesting half and should not be argued about.</b>
     */
    private static final List<Class<?>> INBOUND_LICENCE =
            List.of(QuestionDraft.class, QuestionEdit.class);

    /**
     * Key-bearing records the server <b>returns</b>. These are the licence.
     *
     * <h2>{@code QuestionDetail} — the editor's load and the detail pane (E6.1/E6.3)</h2>
     *
     * <p>Authoring <em>is</em> looking at which answer is right, so {@code QUESTION_GET} carries
     * correctness where the take-exam wire deliberately cannot. What licenses it is not this
     * list: it is the verb's gate, which is staff-only
     * ({@code TEACHER, COORDINATOR, PRINCIPAL}) plus per-role scope resolved server-side, with
     * everything out of reach answering {@code NOT_FOUND} indistinguishably from a question that
     * never existed. The principal is included by the lead's ruling of 2026-08-21: one detail
     * type for every staff reader, rather than a second keyless projection for a distinction
     * whose threat model is students and not staff.
     *
     * <h2>{@code QuestionVersionDetail} — read-only history (E6.3, F2.3)</h2>
     *
     * <p>Same audience, same staff-only scoped verb ({@code QUESTION_VERSIONS}). A history that
     * showed the stem and options of v1 but hid which one was right would be a diff a teacher
     * cannot read, which is the entire use for it.
     *
     * <p><b>If the E6 authorization tests ever go away, these two stop being licensed</b> and
     * come back off this list, on exactly the terms {@code CorrectnessLeakGuardTest} states for
     * its sanctioned suffixes. {@code docs/contracts/BANK_WIRE_CONTRACT.md} records the
     * dependency.
     */
    private static final List<Class<?>> OUTBOUND_LICENCE =
            List.of(QuestionDetail.class, QuestionVersionDetail.class);

    /**
     * LEGACY - retirement PR scheduled after E6 merges (retires LegacyQuestionHandlers,
     * QuestionDAO, the legacy screen, the E18.4 guarded-update flow).
     *
     * <p>Dated 2026-08-21, by the lead's ruling. {@code Question.answer} is an answer key that
     * this guard cannot see, so for as long as these two live in {@code common.dto.bank} the
     * claim "the build says so" is qualified. They are listed here rather than left out so the
     * qualification is impossible to miss, and {@link #theLegacyExclusionIsNamedAndStillReal()}
     * fails the day the pair is retired, which is the day this entry should be deleted.
     */
    private static final List<Class<?>> LEGACY_NOT_COVERED =
            List.of(Question.class, QuestionUpdate.class);

    @Test
    @DisplayName("no record on the bank wire carries an answer key unless it is licensed by name")
    void onlyLicensedBankDtosCarryCorrectness() {
        List<Class<?>> records = classesIn().stream().filter(Class::isRecord).toList();

        assertThat(records)
                .as("the scan must actually find the bank wire package")
                .hasSizeGreaterThanOrEqualTo(14);

        Set<Class<?>> licensed = new LinkedHashSet<>(INBOUND_LICENCE);
        licensed.addAll(OUTBOUND_LICENCE);

        List<String> unlicensed = new ArrayList<>();
        for (Class<?> dto : records) {
            if (licensed.contains(dto) || LEGACY_NOT_COVERED.contains(dto)) {
                continue;
            }
            if (componentsOf(dto).stream().anyMatch(CorrectnessNames::suggestsCorrectness)) {
                unlicensed.add(dto.getSimpleName());
            }
        }

        assertThat(unlicensed)
                .as("these bank DTOs carry which answer is right and are on no allow-list. "
                        + "Either they must not, or BankWireLeakGuardTest gains an entry saying "
                        + "who receives them and under which gate (F2.1, E2.12)")
                .isEmpty();
    }

    @Test
    @DisplayName("the same holds by declared field, whatever the record says")
    void noUnlicensedBankDtoDeclaresCorrectness() {
        // Components and fields are the same thing for a record today, but a future
        // non-record DTO in this package would slip past the check above - which is exactly
        // how the legacy Question already does.
        Set<Class<?>> known = new LinkedHashSet<>(INBOUND_LICENCE);
        known.addAll(OUTBOUND_LICENCE);
        known.addAll(LEGACY_NOT_COVERED);

        for (Class<?> dto : classesIn()) {
            if (known.contains(dto)) {
                continue;
            }
            assertThat(CorrectnessNames.carriesCorrectness(dto))
                    .as("%s must not declare an answer key field", dto.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the licence is split inbound/outbound, and neither half can cover for the other")
    void theLicenceIsSplitByDirection() {
        // A single flat list is how a future outbound DTO gets waved through by pointing at
        // QuestionDraft. These are two different arguments and are asserted as two.
        assertThat(INBOUND_LICENCE)
                .containsExactlyInAnyOrder(QuestionDraft.class, QuestionEdit.class);
        assertThat(OUTBOUND_LICENCE)
                .containsExactlyInAnyOrder(QuestionDetail.class, QuestionVersionDetail.class);
        assertThat(INBOUND_LICENCE).doesNotContainAnyElementsOf(OUTBOUND_LICENCE);
    }

    @Test
    @DisplayName("every allow-list entry actually trips the predicate, so no entry is stale")
    void theAllowListIsMinimal() {
        // An entry that no longer carries a key is an entry that licenses nothing and hides
        // the next one. Both halves are checked, so removing correctness from a licensed type
        // fails here rather than leaving a permission lying around.
        for (Class<?> licensed : INBOUND_LICENCE) {
            assertThat(componentsOf(licensed))
                    .as("%s is on the inbound allow-list but carries no answer key any more; "
                            + "delete the entry", licensed.getSimpleName())
                    .anyMatch(CorrectnessNames::suggestsCorrectness);
        }
        for (Class<?> licensed : OUTBOUND_LICENCE) {
            assertThat(componentsOf(licensed))
                    .as("%s is on the outbound allow-list but carries no answer key any more; "
                            + "delete the entry", licensed.getSimpleName())
                    .anyMatch(CorrectnessNames::suggestsCorrectness);
        }
    }

    @Test
    @DisplayName("that check can fail: the detail really does trip it and the list row does not")
    void theCheckHasTeeth() {
        // Without this, every check above would also pass if suggestsCorrectness() stopped
        // recognising anything at all. QuestionDetail is the honest positive: it carries the
        // key by design, gated by a staff-only scoped verb.
        assertThat(CorrectnessNames.carriesCorrectness(QuestionDetail.class)).isTrue();
        assertThat(CorrectnessNames.carriesCorrectness(QuestionVersionDetail.class)).isTrue();
        assertThat(CorrectnessNames.carriesCorrectness(QuestionDraft.class)).isTrue();
        assertThat(CorrectnessNames.carriesCorrectness(QuestionEdit.class)).isTrue();

        assertThat(CorrectnessNames.carriesCorrectness(BankQuestionRow.class)).isFalse();
    }

    @Test
    @DisplayName("the bank list row is keyless, answerless and byteless")
    void theListRowIsTheKeylessShape() {
        // The high-volume payload: forty rows for a browse, and the thing on screen when
        // somebody shares a screenshot. Pinning the shape means adding a key to it is a
        // deliberate edit to this assertion rather than a field that appeared in a mapper.
        assertThat(componentsOf(BankQuestionRow.class)).containsExactly(
                "displayId5", "courseCode", "courseName", "text", "topic", "difficulty",
                "latestVersionNo", "hasImage", "lastVersionAt");
    }

    @Test
    @DisplayName("no bank DTO carries a lock field: E18.8 owns that and duplicating it would drift")
    void noBankDtoCarriesALock() {
        // Stated in the contract as a deliberate absence, so it gets a test rather than a
        // sentence. A lockVersion here would also be inert: `questions` never changes when a
        // version is inserted, so @Version never increments. baseVersionNo does the work.
        for (Class<?> dto : classesIn().stream().filter(Class::isRecord).toList()) {
            if (LEGACY_NOT_COVERED.contains(dto)) {
                continue;
            }
            assertThat(componentsOf(dto))
                    .as("%s must not carry lock state; the bank list merges E18.8's snapshot "
                            + "and pushes onto its rows client-side (F10.0)", dto.getSimpleName())
                    .noneMatch(BankWireLeakGuardTest::suggestsLock);
        }
    }

    @Test
    @DisplayName("the legacy exclusion is named, dated, and still describes something real")
    void theLegacyExclusionIsNamedAndStillReal() {
        // The day the retirement PR lands, this fails and takes LEGACY_NOT_COVERED with it.
        List<Class<?>> present = classesIn();
        assertThat(present)
                .as("LEGACY_NOT_COVERED names types that have been retired; delete the entry "
                        + "and this test with it")
                .containsAll(LEGACY_NOT_COVERED);

        // And it is honest about why the exclusion is needed: `answer` is a real key that the
        // shared predicate does not and must not match, because answer1..answer4 are the four
        // options a student is supposed to see.
        assertThat(Arrays.stream(Question.class.getDeclaredFields()).map(f -> f.getName()))
                .contains("answer");
        assertThat(CorrectnessNames.suggestsCorrectness("answer")).isFalse();
        assertThat(CorrectnessNames.carriesCorrectness(Question.class))
                .as("if this ever goes true the legacy pair is visible to the scan and the "
                        + "exclusion can go")
                .isFalse();
        assertThat(Question.class.isRecord())
                .as("the second reason the scan is blind to it")
                .isFalse();
    }

    /**
     * @param name a record component name
     * @return whether it reads like lock state
     *
     * <p>Camel-case aware rather than a lower-cased {@code contains("lock")}, which matches
     * {@code blockingExams} — the delete refusal's list of exams, which is not lock state and
     * whose accidental red-line would be "fixed" by weakening the check.
     */
    private static boolean suggestsLock(String name) {
        return name.startsWith("lock") || name.contains("Lock");
    }

    private static List<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<Class<?>> classesIn() {
        try (Stream<Path> files = Files.list(COMPILED_BANK_DTOS)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.equals("package-info.class"))
                    .filter(name -> !name.contains("$"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .<Class<?>>map(BankWireLeakGuardTest::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not scan " + COMPILED_BANK_DTOS, e);
        }
    }

    private static Class<?> load(String simpleName) {
        try {
            return Class.forName(PACKAGE + "." + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled class not loadable: " + simpleName, e);
        }
    }
}
