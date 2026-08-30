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

    /**
     * Every stylesheet the app loads at runtime.
     *
     * <p>{@code /css/app.css} was the seventh: the prototype's sheet, loaded onto the legacy
     * bank screen's own root rather than the Scene so its styling could not leak into the design
     * system. It had exactly one loader, and the retirement PR deleted both.
     */
    private static final List<String> SHIPPED = List.of(
            "/css/hsts.css",
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

    /**
     * Round 3's selectors are in the parsed rules too (2026-08-29, manual round 3).
     *
     * <p>Same claim as the wave-2 group above and the same reason: a rule inside a block the
     * parser gave up on is in the file and not in the stylesheet. U-29's menu rules are the ones
     * this matters most for, because their whole job is to WIN a specificity tie against
     * {@code .button.primary .label} - a rule that never parsed would leave the tie uncontested
     * and the bug exactly where it was, with a green build behind it.
     */
    @Test
    @DisplayName("⚑ round 3's menu and chip selectors survive parsing")
    void theRoundThreeSelectorsSurviveParsing() {
        List<String> selectors = parse("/css/hsts.css").getRules().stream()
                .map(Rule::getSelectors)
                .flatMap(List::stream)
                .map(StylesheetParseTest::normalise)
                .collect(Collectors.toList());

        assertThat(selectors).as("U-29: the dropdown and the navbar profile menu").contains(
                canonical(".context-menu"),
                canonical(".menu-item"),
                canonical(".context-menu .menu-item .label"),
                canonical(".context-menu .menu-item:focused .label"),
                canonical(".context-menu .menu-item:disabled .label"));
        assertThat(selectors).as("U-32: the compact chip and its tone-coloured glyph").contains(
                canonical(".hsts-chip.compact"),
                canonical(".hsts-chip.ok .chip-icon"),
                canonical(".hsts-chip.danger .chip-icon"));
        assertThat(selectors).as("U-30: the builder's course line").contains(
                canonical(".exam-builder-course"));
    }

    /**
     * The wider dialog is a rule that parsed, and it still beats the cap it overrides ⚑
     * (2026-08-30, live session, U-47).
     *
     * <p>{@code .hsts-dialog} caps every dialog in the app at a 520px reading measure, which
     * is right for a dialog that is mostly a sentence and wrong for one whose row is a date
     * picker and two clock spinners. {@code .hsts-dialog.wide} is the opt-out, and it is worth
     * asserting for the same reason U-29's menu block is: a declaration JavaFX gave up on is
     * in the file and not in the stylesheet, and the failure mode is the defect still being
     * there with a green build behind it.
     *
     * <p>Two classes beat one, so this wins on specificity wherever it sits in the file. The
     * assertion is that both caps are still declared and that the wider one is the wider one,
     * which is the part a careless edit could quietly reverse.
     */
    @Test
    @DisplayName("⚑ U-47: .hsts-dialog.wide survives parsing and is wider than the base cap")
    void theWideDialogRuleIsApplied() {
        List<String> selectors = parse("/css/hsts.css").getRules().stream()
                .map(Rule::getSelectors)
                .flatMap(List::stream)
                .map(StylesheetParseTest::normalise)
                .collect(Collectors.toList());

        assertThat(selectors).as("the create-release dialog's own width").contains(
                canonical(".hsts-dialog.wide"),
                canonical(".release-create-dialog .release-moment-row"),
                canonical(".release-create-dialog .release-moment-colon"));

        double base = maxWidthOf(".hsts-dialog");
        double wide = maxWidthOf(".hsts-dialog.wide");
        assertThat(base).as("the reading measure every other dialog keeps").isEqualTo(520);
        assertThat(wide)
                .as("a `wide` that is not wider is a style class that does nothing")
                .isGreaterThan(base);
    }

    /**
     * @return the {@code -fx-max-width} declared on the given selector, in px
     */
    private static double maxWidthOf(String selector) {
        return parse("/css/hsts.css").getRules().stream()
                .filter(rule -> rule.getSelectors().stream()
                        .map(StylesheetParseTest::normalise)
                        .anyMatch(canonical(selector)::equals))
                .flatMap(rule -> rule.getDeclarations().stream())
                .filter(declaration -> "-fx-max-width".equals(declaration.getProperty()))
                .map(declaration -> (Number) declaration.getParsedValue().convert(null))
                .mapToDouble(Number::doubleValue)
                .max()
                .orElseThrow(() -> new AssertionError(selector + " declares no -fx-max-width"));
    }

    /**
     * The menu rules come AFTER the button rules, which is what breaks the tie ⚑ (U-29).
     *
     * <p>{@code .context-menu .menu-item .label} and {@code .button.primary .label} are both
     * three-class selectors. JavaFX resolves equal specificity by position in the stylesheet, so
     * the menu block being later in the file is not tidiness, it is the fix: a ContextMenu
     * resolves its CSS through {@code PopupControl.getStyleableParent()}, which is the owner
     * node, so the items of a MenuButton styled {@code .button.primary} were inheriting
     * {@code -hsts-on-accent} - white on a white menu in light mode, near-black on a dark one.
     *
     * <p>Asserted on the file rather than on a rendered colour, because a rendered colour needs
     * a booted toolkit, a shown popup and a palette, and this needs none of the three.
     */
    @Test
    @DisplayName("⚑ U-29: the menu block is later in the file than the button block")
    void theMenuRulesOutrankTheButtonInk() throws IOException {
        String css = new String(StylesheetParseTest.class
                .getResourceAsStream("/css/hsts.css").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        int buttonInk = css.indexOf(".button.primary .label");
        int menuInk = css.indexOf(".context-menu .menu-item .label");

        assertThat(buttonInk).as("the rule that caused U-29 is still in the file").isNotEqualTo(-1);
        assertThat(menuInk).as("and so is the one that has to beat it").isNotEqualTo(-1);
        assertThat(menuInk)
                .as("equal specificity is broken by order, so the menu rule must come last")
                .isGreaterThan(buttonInk);
    }

    /**
     * A source row is not dressed as something to press ⚑ (U-33).
     *
     * <p>{@code .source-row} shared the accent hover border with {@code .history-row}, which is
     * genuinely openable. A source row's actions are the Edit and Remove buttons sitting on it,
     * so the border was advertising a press whose only visible outcome was the lock banner
     * sliding in and back out again.
     *
     * <p>Checked here rather than in the interaction test because a {@code :hover} rule is a
     * fact about the stylesheet: TestFX can move a pointer, but asserting the resulting border
     * colour means reading a rendered pixel, and the rule's absence is the actual claim.
     */
    @Test
    @DisplayName("⚑ U-33: .source-row has no hover treatment, .history-row still does")
    void aSourceRowIsNotPressable() {
        List<String> hovered = parse("/css/hsts.css").getRules().stream()
                .map(Rule::getSelectors)
                .flatMap(List::stream)
                .map(Selector::toString)
                .filter(selector -> selector.contains(":hover"))
                .toList();

        assertThat(hovered)
                .as("the row carries no action of its own, so it must not offer a press")
                .noneMatch(selector -> selector.contains("source-row"));
        assertThat(hovered)
                .as("guard against the guard: a history row IS openable and keeps the tint, so "
                        + "a stylesheet that had simply lost every hover rule fails here")
                .anyMatch(selector -> selector.contains("history-row"));
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
