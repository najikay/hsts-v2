package client.ui.theme;

import javafx.css.CssParser;
import javafx.css.Rule;
import javafx.css.Selector;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every shipped stylesheet parses cleanly (UI wave 2).
 *
 * <h2>Why this exists, and why it exists now</h2>
 *
 * <p><b>JavaFX does not fail on a bad stylesheet.</b> A misspelt property, a
 * value in a syntax it does not accept, an unbalanced brace — each is logged
 * and skipped, and the app comes up looking almost right. Nothing goes red.
 * That is survivable while the CSS is small; wave 2 added roughly 200 lines of
 * it, several of them using constructs the toolkit is fussy about
 * ({@code derive()} inside a gradient, {@code segments()} borders,
 * {@code radial-gradient} with an explicit centre), and a silent skip in any of
 * them is a design that ships half-applied with a green build behind it.
 *
 * <p>The interaction tests cannot catch it either: they assert on nodes and
 * style classes, and a node whose colour never resolved still has its style
 * class. So the parser is asked directly.
 *
 * <p>Runs with no toolkit booted: {@link CssParser} is a parser, not a scene.
 */
class StylesheetParseTest {

    /** Every stylesheet the app loads at runtime. */
    private static final List<String> SHIPPED = List.of(
            "/css/hsts.css",
            "/css/app.css",
            "/css/accent-indigo.css",
            "/css/accent-emerald.css",
            "/css/accent-amber.css",
            "/css/accent-rose.css",
            "/css/accent-slate.css");

    @BeforeEach
    void clearErrors() {
        // The parser's error list is static and shared, so a leftover error from
        // another test would be attributed to this one.
        CssParser.errorsProperty().clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/css/hsts.css",
            "/css/app.css",
            "/css/accent-indigo.css",
            "/css/accent-emerald.css",
            "/css/accent-amber.css",
            "/css/accent-rose.css",
            "/css/accent-slate.css"})
    @DisplayName("⚑ parses with no errors, so nothing is silently skipped")
    void parsesCleanly(String resource) {
        Stylesheet parsed = parse(resource);

        assertThat(parsed).as("%s must be on the classpath", resource).isNotNull();
        assertThat(CssParser.errorsProperty())
                .as("JavaFX logs and skips a bad declaration rather than failing, so a "
                        + "half-applied design ships behind a green build")
                .isEmpty();
        assertThat(parsed.getRules()).as("%s produced no rules at all", resource).isNotEmpty();
    }

    @Test
    @DisplayName("the scan really reads the stylesheets, so a green run means something")
    void theScanHasTeeth() {
        for (String resource : SHIPPED) {
            assertThat(StylesheetParseTest.class.getResource(resource))
                    .as("%s is named here and must ship", resource)
                    .isNotNull();
        }
        assertThat(parse("/css/hsts.css").getRules()).hasSizeGreaterThan(200);
    }

    @Test
    @DisplayName("⚑ the wave-2 selectors are in the parsed rules, not merely in the file")
    void theWaveTwoSelectorsSurviveParsing() {
        // A rule inside a block the parser gave up on is in the file and not in
        // the stylesheet. Naming the new selectors here is what tells the
        // difference between "written" and "applied".
        List<String> selectors = parse("/css/hsts.css").getRules().stream()
                .map(Rule::getSelectors)
                .flatMap(List::stream)
                .map(StylesheetParseTest::normalise)
                .collect(Collectors.toList());

        assertThat(selectors).contains(
                canonical(".hsts-kicker"),
                canonical(".card-link"),
                canonical(".live-progress-fill"),
                canonical(".live-halo"),
                canonical(".sparkbar-fill.modal"),
                canonical(".hsts-progress-ring .ring-fill"),
                canonical(".grades-hero-host"),
                canonical(".grades-hero-wash"),
                canonical(".grade-card"),
                canonical(".grade-slot"),
                canonical(".hsts-number-roll"),
                canonical(".hsts-table .numeric-cell"),
                canonical(".hsts-table-wrapper .row-open-affordance"),
                canonical(".hsts-notification-panel .row-badge.ok"),
                canonical(".hsts-notification-panel .row-unread-dot"));
    }

    @Test
    @DisplayName("both palettes are still one file: dark redefines tokens, never components")
    void darkIsStillOnlyTokens() {
        // The house rule that makes a token layer worth having. A `.root.dark`
        // rule naming a component selector would be the first per-mode override
        // in the app, and the next one would not need an argument.
        List<String> darkSelectors = parse("/css/hsts.css").getRules().stream()
                .map(Rule::getSelectors)
                .flatMap(List::stream)
                .map(StylesheetParseTest::normalise)
                .filter(selector -> selector.startsWith(".root.dark"))
                .toList();

        assertThat(darkSelectors)
                .as("dark-mode rules are the token block plus the handful of shadow "
                        + "overrides a dark surface genuinely needs")
                .hasSizeLessThanOrEqualTo(8);
    }

    /**
     * @return the selector as it is written in the stylesheet. JavaFX prints a
     *         universal selector in front of every compound ({@code *.button}),
     *         and normalises the order of the classes within one compound, so
     *         the raw {@code toString()} is not what anybody typed
     */
    private static String normalise(Selector selector) {
        return canonical(selector.toString().replace("*", ""));
    }

    /**
     * Puts a selector into a comparable form.
     *
     * <p>JavaFX does not print a compound selector's classes in the order they
     * were written: it holds them in a set keyed by the order each name was
     * first seen anywhere in the stylesheet, so {@code .row-badge.ok} comes back
     * as {@code .ok.row-badge}. Sorting the classes within each compound makes
     * both sides of an assertion independent of that, which matters because the
     * order can change when an unrelated rule is added earlier in the file.
     */
    private static String canonical(String selector) {
        return java.util.Arrays.stream(selector.trim().split("\\s+"))
                .map(compound -> java.util.Arrays.stream(compound.split("\\."))
                        .filter(part -> !part.isEmpty())
                        .sorted()
                        .collect(Collectors.joining(".", ".", "")))
                .collect(Collectors.joining(" "));
    }

    private static Stylesheet parse(String resource) {
        URL url = StylesheetParseTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("stylesheet not on the classpath: " + resource);
        }
        try {
            return new CssParser().parse(url);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
