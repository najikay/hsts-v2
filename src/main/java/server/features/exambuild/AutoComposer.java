package server.features.exambuild;

import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.Shortfall;
import common.dto.authoring.TopicQuota;
import server.db.projections.AutoCandidate;
import server.features.bank.QuestionValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Proposes a composition from a criteria grid, or says exactly what is missing
 * (Logic tier, E7.4 ⚑ — F3.2, F3.3, contract §7).
 *
 * <p><b>It writes nothing and holds no session.</b> A pool of candidates goes in and a result
 * comes out; the caller does the reading. That is what makes T-3.5's "No exam is created" true by
 * construction rather than by a rollback that has to work, and it is why every rule below is a
 * unit test rather than an integration one.
 *
 * <h2>The report is the feature, not the consolation prize</h2>
 *
 * <p>F3.3 does not ask that generation fail politely. It asks that the report state exactly what
 * is missing, and the PRD writes the sentence out: <em>Topic 'Algebra': requested 5 Hard, bank has
 * 2</em>. The seed carries one deliberately thin topic (Recursion in Java, two questions, no Hard)
 * so that sentence can be produced live without anybody touching the database. T-3.5 and T-3.6 are
 * the two shots at it.
 *
 * <p>Which makes <b>{@code available}</b> the number this whole class is judged on. §7.2 property
 * 2: it is the raw count in her own bank, under her own scope, and never a count net of what
 * another quota consumed. She is being invited to go and check it by filtering the bank screen to
 * the same topic and difficulty, so a number she can disprove makes the report worse than nothing.
 * The pool is read once and counted and drawn from in memory precisely so the counting and the
 * picking cannot describe different banks.
 *
 * <h2>Why bucket comparison is exact here, and would not be in general</h2>
 *
 * <p>Deciding whether a family of overlapping demands can all be met is Hall's condition, which in
 * general needs a matching rather than a per-bucket comparison. It collapses to per-bucket
 * comparisons on a family that <b>nests</b>, and §7.3a is what forces nesting: with a graded
 * course-wide quota refused alongside topic quotas, topic pools are pairwise disjoint, a topic's
 * difficulty buckets sit inside that topic, and the course-wide {@code any} pool contains
 * everything. {@link ExamValidator#quotaProblem} enforces that shape, and this class is correct
 * <em>because of it</em>, not independently of it.
 *
 * <p>The same rule is what makes most-constrained-first greedy exact rather than merely sensible.
 * Both properties fail on crossing pools, and the contract records that the failures were
 * reproduced rather than imagined: no bucket short, the request impossible, no row to emit, and
 * the teacher meeting an internal error on the one verb F3.3 exists for.
 *
 * <h2>Topic matching is the service's, not the database's</h2>
 *
 * <p>Buckets are formed with {@link QuestionValidator#sameTopic}, in memory, over a pool the query
 * scoped only by course. The alternative - one query per topic with {@code qv.topic = :topic} -
 * would make the database the authority for the pool while the service stays the authority for
 * the quota buckets, and those two answers diverging silently in Hebrew is precisely P-9.
 * {@code sameTopic} is at least as strict as {@code utf8mb4_unicode_ci} in every dimension
 * (C-7 / ADR-016), so a bucket here can never be wider than the rows the bank screen would show.
 */
public final class AutoComposer {

    private AutoComposer() {
        // stateless - the pool and the criteria are both parameters
    }

    /**
     * Runs the criteria against a pool.
     *
     * <p>Assumes {@link ExamValidator#quotaProblem} has already accepted the request: the shape
     * rule, the duplicate-topic rule and the points ceiling are all preconditions of the
     * reasoning above, not things re-checked here. Calling this with criteria that failed
     * validation is a programming error, and the nesting argument does not hold for them.
     *
     * @param request the accepted criteria
     * @param pool    every live latest-version question in the exam's course, from
     *                {@code ExamBuildRepository.findAutoCandidates}
     * @return a proposal whose points already total 100, or every shortfall that stands between
     *         her and one
     */
    public static AutoComposeResult compose(AutoComposeRequest request, List<AutoCandidate> pool) {
        Objects.requireNonNull(request, "request");
        List<AutoCandidate> candidates = pool == null ? List.of() : pool;

        List<Demand> demands = demandsOf(request, candidates);
        List<Shortfall> shortfalls = shortfallsOf(request, demands, candidates);
        if (!shortfalls.isEmpty()) {
            return AutoComposeResult.infeasible(shortfalls);
        }
        return AutoComposeResult.composed(select(demands, request.seed()));
    }

    // ===================== The buckets ====================================

    /**
     * One leaf demand: a count, and the candidates that could satisfy it.
     *
     * <p>Leaf means the level a question is actually drawn from, so a topic's {@code any} bucket
     * is a leaf beside its three graded ones even though it overlaps them. The enclosing levels
     * exist only for counting, in {@link #shortfallsOf}.
     */
    private record Demand(String topic, server.db.entities.Difficulty difficulty, int wanted,
                          List<AutoCandidate> pool) {
    }

    private static List<Demand> demandsOf(AutoComposeRequest request,
                                          List<AutoCandidate> candidates) {
        List<Demand> demands = new ArrayList<>();
        for (TopicQuota quota : request.quotas()) {
            if (quota == null || quota.isEmpty()) {
                continue;
            }
            List<AutoCandidate> inScope = quota.isCourseWide()
                    ? candidates
                    : candidates.stream()
                            .filter(candidate -> QuestionValidator.sameTopic(quota.topic(),
                                    candidate.topic()))
                            .toList();
            addGraded(demands, quota, inScope);
            if (quota.any() > 0) {
                demands.add(new Demand(quota.topic(), null, quota.any(), inScope));
            }
        }
        return demands;
    }

    private static void addGraded(List<Demand> demands, TopicQuota quota,
                                  List<AutoCandidate> inScope) {
        addIfWanted(demands, quota.topic(), server.db.entities.Difficulty.EASY, quota.easy(),
                inScope);
        addIfWanted(demands, quota.topic(), server.db.entities.Difficulty.MEDIUM, quota.medium(),
                inScope);
        addIfWanted(demands, quota.topic(), server.db.entities.Difficulty.HARD, quota.hard(),
                inScope);
    }

    private static void addIfWanted(List<Demand> demands, String topic,
                                    server.db.entities.Difficulty difficulty, int wanted,
                                    List<AutoCandidate> inScope) {
        if (wanted <= 0) {
            return;
        }
        demands.add(new Demand(topic, difficulty, wanted,
                inScope.stream().filter(c -> c.difficulty() == difficulty).toList()));
    }

    // ===================== The report =====================================

    /**
     * Every shortfall, at every level that is over its own raw supply (§7.2 property 1, §7.3).
     *
     * <p>Three levels, and a request can be short at more than one of them at once. A teacher out
     * of Recursion Hard <em>and</em> over her course's total supply is told both, because fixing
     * one does not fix the other - which is why the aggregate row sits beside the raw-short rows
     * rather than replacing them.
     *
     * <p>Emitted most specific first, because that is the order she can act on: the leaf row names
     * a box on her screen, the aggregate row names the whole request.
     */
    private static List<Shortfall> shortfallsOf(AutoComposeRequest request, List<Demand> demands,
                                                List<AutoCandidate> candidates) {
        List<Shortfall> shortfalls = new ArrayList<>();

        // 1. Leaves: this exact bucket, against its own raw supply.
        for (Demand demand : demands) {
            if (demand.wanted() > demand.pool().size()) {
                shortfalls.add(new Shortfall(demand.topic(), wire(demand.difficulty()),
                        demand.wanted(), demand.pool().size()));
            }
        }

        // 2. Topic level: a topic's graded buckets and its `any` bucket competing for one supply.
        //    Reported as (topic, null, topicDemand, topicSupply) per §7.3's "one level down".
        //
        //    ONLY on contention - two or more buckets non-zero. §7.3's rule is about buckets
        //    competing for one supply, and with a single bucket there is nothing to compete with:
        //    the leaf above already says everything true. Emitting it anyway pairs a real count
        //    with a demand that does not belong to it, which is the exact sentence §7.3 calls
        //    disprovable. Asking for three Recursion HARD from a topic holding two questions,
        //    neither Hard, produced "requested 3 Hard, bank has 0" - correct - followed by
        //    "requested 3 questions, bank has 2", about a request she never made.
        for (TopicQuota quota : request.quotas()) {
            if (quota == null || quota.isCourseWide() || quota.isEmpty()
                    || bucketsUsed(quota) < 2) {
                continue;
            }
            long supply = candidates.stream()
                    .filter(c -> QuestionValidator.sameTopic(quota.topic(), c.topic()))
                    .count();
            if (quota.total() > supply) {
                // SUPERSEDES a same-topic row of the same shape rather than joining it ⚑
                //
                // §7.1 gives (topic, null, requested, available) exactly one sentence, so a leaf
                // row for this topic's `any` bucket and this aggregate are indistinguishable on
                // the wire: the client cannot render them apart. Emitting both showed her
                //
                //   Topic "Recursion": requested 3 questions, bank has 2.
                //   Topic "Recursion": requested 4 questions, bank has 2.
                //
                // which reads as two problems, or as a report that cannot count. addIfNew could
                // not catch it because the two rows differ in `requested`, and neither the
                // property test nor the missing()>0 check can, because both rows are individually
                // correct.
                //
                // The aggregate wins because it is the number she can act on: it covers her whole
                // demand on the topic, so one edit fixes it, where acting on the `any` leaf alone
                // leaves the graded buckets still over. Contract §7.3 agrees by its own letter -
                // its aggregate rule is written for "the smallest enclosing bucket whose summed
                // demand exceeds its raw supply".
                replaceTopicWide(shortfalls,
                        new Shortfall(quota.topic(), null, quota.total(), (int) supply));
            }
        }

        // 3. Course level: the union of everything asked for, against the whole pool. This is the
        //    row §7.3's rule exists for - three Recursion and eight course-wide against a bank of
        //    ten, where neither quota is short and eleven questions are asked for.
        int totalWanted = request.totalRequested();
        if (totalWanted > candidates.size()) {
            addIfNew(shortfalls, new Shortfall(null, null, totalWanted, candidates.size()));
        }
        return shortfalls;
    }

    /**
     * How many of a quota's four buckets actually ask for something.
     *
     * <p>Two or more is what {@code contention} means at the topic level: the buckets draw on one
     * supply and can exhaust it between them while none is short alone. One bucket cannot compete
     * with itself, so its leaf row is the whole truth and an aggregate beside it would be a
     * second sentence about a request she did not make.
     */
    private static int bucketsUsed(TopicQuota quota) {
        int used = 0;
        if (quota.easy() > 0) {
            used++;
        }
        if (quota.medium() > 0) {
            used++;
        }
        if (quota.hard() > 0) {
            used++;
        }
        if (quota.any() > 0) {
            used++;
        }
        return used;
    }

    /**
     * Puts the topic-wide row in, dropping any earlier row of the same shape for that topic.
     *
     * <p>"Same shape" means topic-scoped with a null difficulty, which is the only other row that
     * renders as §7.1's topic sentence. A graded leaf for the same topic - "requested 3 Hard,
     * bank has 0" - says something different and stays: she is short of Hard specifically <em>and</em>
     * over the topic overall, and fixing one does not fix the other, which is §7.2 property 1.
     */
    private static void replaceTopicWide(List<Shortfall> shortfalls, Shortfall aggregate) {
        shortfalls.removeIf(existing -> existing.isTopicScoped()
                && !existing.isDifficultyScoped()
                && QuestionValidator.sameTopic(existing.topic(), aggregate.topic()));
        shortfalls.add(aggregate);
    }

    /**
     * Adds a row unless one saying the same thing is already there.
     *
     * <p><b>Every level can restate a level below it, and the duplicate is invisible in the
     * code.</b> A lone course-wide quota for fifteen against a bank of ten produces the identical
     * row twice: once as its own leaf, once as the course-level union, because with one quota the
     * union <em>is</em> that quota. The topic level does the same for a topic whose only demand is
     * its {@code any} bucket. She would read one problem printed twice under two sentences and
     * reasonably conclude there were two.
     *
     * <p>Guarded here rather than at each site so a fourth level cannot be added without it.
     * Topics compare through {@link QuestionValidator#sameTopic} rather than {@code equals},
     * because two rows differing only by case or by a Hebrew final form are one bucket to the
     * database and would otherwise both survive this filter.
     */
    private static void addIfNew(List<Shortfall> shortfalls, Shortfall candidate) {
        for (Shortfall existing : shortfalls) {
            boolean sameTopic = existing.isTopicScoped() == candidate.isTopicScoped()
                    && (!existing.isTopicScoped()
                            || QuestionValidator.sameTopic(existing.topic(), candidate.topic()));
            if (sameTopic
                    && existing.difficulty() == candidate.difficulty()
                    && existing.requested() == candidate.requested()
                    && existing.available() == candidate.available()) {
                return;
            }
        }
        shortfalls.add(candidate);
    }

    // ===================== The selection ==================================

    /**
     * Picks a question for every demand (§7.4).
     *
     * <p><b>Most-constrained-first</b>, by candidate count ascending, so a narrow quota does not
     * lose its only candidates to a wide one that had alternatives. Without it, "3 Recursion" and
     * "10 any topic" can fail against a bank that holds thirteen suitable questions, and the
     * report would then name a shortfall that is not real - the worst failure available here,
     * because it is one she can disprove.
     *
     * <p><b>No question twice, by {@code questionId} rather than by version</b> (§7.4, §5.2's rule
     * applied during selection instead of discovered at save). Two versions of one question are
     * one question on a paper, and the pool holds only latest versions anyway; keying on the
     * owning id is what keeps that true if the pool ever widens.
     */
    private static List<ComposedQuestion> select(List<Demand> demands, Long seed) {
        List<Demand> byConstraint = new ArrayList<>(demands);
        byConstraint.sort((left, right) -> Integer.compare(left.pool().size(),
                right.pool().size()));

        Random random = seed == null ? new Random() : new Random(seed);
        Set<Long> takenQuestions = new LinkedHashSet<>();
        Map<Long, AutoCandidate> chosen = new LinkedHashMap<>();

        for (Demand demand : byConstraint) {
            List<AutoCandidate> shuffled = new ArrayList<>(demand.pool());
            // Shuffled rather than index-sampled: over a pool the query already ordered by display
            // id, one shuffle with a seeded Random is reproducible and unbiased, and a teacher who
            // reports a strange set can have it reproduced from the seed in the log (§7.5).
            Collections.shuffle(shuffled, random);
            int still = demand.wanted();
            for (AutoCandidate candidate : shuffled) {
                if (still == 0) {
                    break;
                }
                if (takenQuestions.add(candidate.questionId())) {
                    chosen.put(candidate.questionVersionId(), candidate);
                    still--;
                }
            }
            if (still > 0) {
                // Unreachable while §7.3a holds: on a nesting family the bucket comparisons in
                // shortfallsOf are complete, so a demand that passed them cannot run dry here.
                // Loud rather than a short paper, because a proposal missing questions would be
                // saved in one click and only then meet the sum-to-100 rule.
                throw new IllegalStateException(
                        "auto-compose ran dry on " + describe(demand) + " with " + still
                                + " still wanted, after the shortfall checks passed. The nesting "
                                + "argument in contract 7.3a does not hold for these criteria.");
            }
        }
        return withPoints(new ArrayList<>(chosen.values()));
    }

    /**
     * Spreads exactly 100 points across the proposal (§7.4).
     *
     * <p>As evenly as the count allows, remainder on the earliest: three questions become 34, 33,
     * 33. So the auto path is savable in one click (T-3.4) and every proposal already satisfies
     * §5.1 rather than needing her to fix the sum first.
     *
     * <p>The count cannot exceed {@code POINTS_TOTAL}: {@code quotaProblem} refuses a request
     * asking for more, because {@code MIN_POINTS} is 1 and no spread of 100 points can give 101
     * questions a point each. That precondition is what lets the division below assume a base of
     * at least one.
     */
    private static List<ComposedQuestion> withPoints(List<AutoCandidate> chosen) {
        int count = chosen.size();
        if (count == 0) {
            // AutoComposeResult refuses a proposal with nothing in it, and so does this: a
            // request whose every quota was zero is refused by quotaProblem long before here.
            return List.of();
        }
        int base = ExamCreateRequest.POINTS_TOTAL / count;
        int remainder = ExamCreateRequest.POINTS_TOTAL % count;

        List<ComposedQuestion> composed = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            AutoCandidate candidate = chosen.get(index);
            int points = base + (index < remainder ? 1 : 0);
            // Pinned and latest are the same row, and that is a property of the query rather
            // than an assumption: findAutoCandidates selects only versions whose versionNo IS
            // the correlated maximum, so a proposal can never carry a superseded version. Both
            // the number and the id therefore repeat the candidate's own, and a proposal never
            // arrives already wearing E7.7's badge.
            composed.add(new ComposedQuestion(candidate.questionVersionId(),
                    candidate.displayId5(), index + 1, points, candidate.text(),
                    candidate.topic(), wire(candidate.difficulty()), candidate.hasImage(),
                    candidate.versionNo(), candidate.versionNo(), candidate.questionVersionId()));
        }
        return composed;
    }

    // ===================== Small things ===================================

    /** The entity enum to the wire one, by name, as every service boundary here does. */
    private static common.dto.bank.Difficulty wire(server.db.entities.Difficulty difficulty) {
        return difficulty == null ? null : common.dto.bank.Difficulty.valueOf(difficulty.name());
    }

    private static String describe(Demand demand) {
        String topic = demand.topic() == null ? "the whole course" : "topic '" + demand.topic() + "'";
        String difficulty = demand.difficulty() == null ? "any difficulty"
                : demand.difficulty().name().toLowerCase(java.util.Locale.ROOT);
        return topic + " / " + difficulty;
    }
}
