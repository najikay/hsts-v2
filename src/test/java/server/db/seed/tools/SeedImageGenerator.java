package server.db.seed.tools;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Draws the ten seeded illustrations (B-8), one PNG per illustrated question.
 *
 * <h2>This is a tool, not a test ⚑</h2>
 *
 * <p>It lives in the test tree so it never ships in the production jar - the lead's ruling of
 * 2026-08-26 - but it is <b>deliberately invoked</b> via {@link #main} and is not a JUnit test.
 * That is not a style preference: it writes into {@code src/main/resources}, and a test that did
 * so would rewrite committed files on every {@code mvn test} and leave the working tree dirty,
 * which reads as an unexplained diff and can fail a clean-tree check in CI.
 *
 * <h2>Why committed source and committed output both exist</h2>
 *
 * <p>The lead's ruling: source that draws the images beats ten opaque binaries, because history
 * then carries the <em>intent</em> - this file says what each picture is meant to show, which a
 * PNG cannot. The PNGs are committed too because {@code QuestionBankSection} loads them as
 * classpath resources at seed time and must work from a packaged jar with no working copy.
 *
 * <p><b>What is NOT guaranteed.</b> Regenerating does not reproduce the committed bytes on a
 * different machine: four of these carry text, and Java2D maps {@code SANS_SERIF} to whatever
 * physical font the host has, so glyph rasterisation differs between Windows and CI's Linux. A
 * byte-equality test would pass for its author and fail everywhere else. {@code SeedImagesTest}
 * therefore checks that every flagged question resolves a decodable, correctly sized, non-blank
 * resource - it does <b>not</b> check that the committed PNG is what this file would draw today.
 * If you change a drawing, rerun this and commit the output; nothing will catch it if you do not.
 *
 * <p>The plotted questions use no text at all, so those are stable by construction.
 *
 * <h2>No picture resolves its own multiple choice ⚑</h2>
 *
 * <p>An illustration that hands over the answer is worse than no illustration at a defence, and
 * it is the thing an examiner notices first. <b>The line is between showing the object and
 * resolving the choice</b>, and it needs stating because the two are close:
 *
 * <ul>
 *   <li><b>Allowed:</b> drawing the curve a question is about, and marking the feature it names.
 *       {@code 11005} shows its parabola with the roots dotted; the axes carry no tick labels, so
 *       she can see there are two positive roots close together and still has to compute them.
 *       That is what a textbook figure does.</li>
 *   <li><b>Not allowed:</b> a picture from which the correct option can be read with no work.
 *       {@code 21006} and {@code 21010} would have printed their answers as labels, so both show
 *       a {@code ?} instead. {@code 11007} asks how many x-axis intercepts its parabola has, so
 *       plotting that parabola <em>is</em> the answer - it is drawn as
 *       {@link #interceptCases} instead.</li>
 * </ul>
 *
 * <p><b>This paragraph did not exist until a cold read found the rule stated on one diagram's
 * javadoc and applied to two of ten pictures</b>, with the plots never checked against it.
 *
 * <p>Run: {@code java -cp <test-classpath> server.db.seed.tools.SeedImageGenerator}
 */
public final class SeedImageGenerator {

    /** Where the loader reads from; see {@code QuestionBankSection}. */
    public static final Path OUTPUT_DIR = Path.of("src", "main", "resources", "seed", "img");

    /** Every generated image is this size. {@code SeedImagesTest} asserts it. */
    public static final int WIDTH = 480;
    public static final int HEIGHT = 320;

    private static final Color INK = new Color(0x1F2933);
    private static final Color CURVE = new Color(0x1D6FB8);
    private static final Color ACCENT = new Color(0xC0392B);
    private static final Color FILL = new Color(0x1D6FB8, true);
    private static final Color SHADE = new Color(29, 111, 184, 60);
    private static final Color PAPER = Color.WHITE;

    private SeedImageGenerator() {
        // tool, invoked through main
    }

    /**
     * @return every illustrated question's display id mapped to its drawing, in seed order.
     *         {@code QuestionBankSection.QUESTIONS} marks exactly these ten {@code image=true}.
     */
    public static Map<String, Drawing> drawings() {
        Map<String, Drawing> byId = new LinkedHashMap<>();
        // "What are the roots of x2 - 5x + 6 = 0?" - roots at 2 and 3, marked on the axis.
        byId.put("11005", g -> plot(g, x -> x * x - 5 * x + 6, -1, 6, -3, 8,
                new double[] {2, 3}, null, null));
        // "What is the vertex of the parabola y = (x - 3)2 + 4?" - vertex marked at (3, 4).
        byId.put("11006", g -> plot(g, x -> (x - 3) * (x - 3) + 4, -1, 7, 0, 16,
                null, new double[] {3, 4}, null));
        // "How many x-axis intercepts does y = x2 + 2x + 5 have?" - the three cases, NOT this one.
        byId.put("11007", SeedImageGenerator::interceptCases);
        // "Solve: x2 - 4 < 0" - the stretch between -2 and 2, where the curve is under the axis.
        byId.put("11010", g -> plot(g, x -> x * x - 4, -4, 4, -6, 10,
                new double[] {-2, 2}, null, new double[] {-2, 2}));
        // "f(x) = x3 - 3x has a local minimum at:" - the dip at x = 1 marked.
        byId.put("12007", g -> plot(g, x -> x * x * x - 3 * x, -2.5, 2.5, -4, 4,
                null, new double[] {1, -2}, null));
        // "Find the area under y = x2 between x=0 and x=3" - that region shaded.
        byId.put("12009", g -> plot(g, x -> x * x, -0.5, 3.5, -1, 10,
                null, null, new double[] {0, 3}));

        // The four conceptual ones. These carry text; see the class javadoc on determinism.
        byId.put("21006", SeedImageGenerator::implementsDiagram);
        byId.put("21010", SeedImageGenerator::recursionDiagram);
        byId.put("22002", SeedImageGenerator::leftJoinDiagram);
        byId.put("22006", SeedImageGenerator::partialDependencyDiagram);
        return byId;
    }

    /** One picture, drawn onto a prepared canvas. */
    @FunctionalInterface
    public interface Drawing {
        void draw(Graphics2D g);
    }

    /** @return the PNG bytes for one drawing, which is what the loader stores. */
    public static byte[] render(Drawing drawing) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(PAPER);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(INK);
        drawing.draw(g);
        g.dispose();
        return toPng(image);
    }

    private static byte[] toPng(BufferedImage image) {
        try (var out = new java.io.ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("no PNG writer available");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not encode PNG", e);
        }
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        for (Map.Entry<String, Drawing> entry : drawings().entrySet()) {
            Path file = OUTPUT_DIR.resolve("q" + entry.getKey() + ".png");
            Files.write(file, render(entry.getValue()));
            System.out.println("wrote " + file);
        }
        System.out.println(drawings().size() + " images written to " + OUTPUT_DIR);
    }

    // ---------------------------------------------------------------- plots

    /**
     * Axes plus one curve, with optional root markers, a labelled point and a shaded strip.
     *
     * @param f       the function to draw
     * @param shade   {@code {from, to}} in x, shaded between the curve and the axis, or null
     */
    private static void plot(Graphics2D g, DoubleUnaryOperator f,
                             double xMin, double xMax, double yMin, double yMax,
                             double[] roots, double[] point, double[] shade) {
        int left = 46;
        int right = WIDTH - 26;
        int top = 26;
        int bottom = HEIGHT - 40;

        DoubleUnaryOperator sx = x -> left + (x - xMin) / (xMax - xMin) * (right - left);
        DoubleUnaryOperator sy = y -> bottom - (y - yMin) / (yMax - yMin) * (bottom - top);

        if (shade != null) {
            GeneralPath area = new GeneralPath(Path2D.WIND_NON_ZERO);
            area.moveTo(sx.applyAsDouble(shade[0]), sy.applyAsDouble(0));
            for (double x = shade[0]; x <= shade[1] + 1e-9; x += (shade[1] - shade[0]) / 160.0) {
                area.lineTo(sx.applyAsDouble(x), sy.applyAsDouble(f.applyAsDouble(x)));
            }
            area.lineTo(sx.applyAsDouble(shade[1]), sy.applyAsDouble(0));
            area.closePath();
            g.setColor(SHADE);
            g.fill(area);
        }

        g.setColor(new Color(0xB6C2CF));
        g.setStroke(new BasicStroke(1f));
        g.draw(new java.awt.geom.Line2D.Double(left, sy.applyAsDouble(0), right, sy.applyAsDouble(0)));
        g.draw(new java.awt.geom.Line2D.Double(sx.applyAsDouble(0), top, sx.applyAsDouble(0), bottom));

        GeneralPath curve = new GeneralPath();
        boolean started = false;
        for (double x = xMin; x <= xMax + 1e-9; x += (xMax - xMin) / 400.0) {
            double y = f.applyAsDouble(x);
            if (y < yMin || y > yMax) {
                started = false;
                continue;
            }
            double px = sx.applyAsDouble(x);
            double py = sy.applyAsDouble(y);
            if (started) {
                curve.lineTo(px, py);
            } else {
                curve.moveTo(px, py);
                started = true;
            }
        }
        g.setColor(CURVE);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(curve);

        if (roots != null) {
            g.setColor(ACCENT);
            for (double root : roots) {
                double px = sx.applyAsDouble(root);
                double py = sy.applyAsDouble(0);
                g.fill(new Ellipse2D.Double(px - 5, py - 5, 10, 10));
            }
        }
        if (point != null) {
            g.setColor(ACCENT);
            double px = sx.applyAsDouble(point[0]);
            double py = sy.applyAsDouble(point[1]);
            g.fill(new Ellipse2D.Double(px - 5.5, py - 5.5, 11, 11));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[] {4f, 4f}, 0f));
            g.draw(new java.awt.geom.Line2D.Double(px, py, px, sy.applyAsDouble(0)));
        }
    }

    // ----------------------------------------------------------- diagrams

    /**
     * The three ways a parabola can meet the x-axis: twice, once, not at all.
     *
     * <p><b>Deliberately not a plot of this question's own parabola.</b> {@code 11007} asks how
     * many intercepts {@code y = x2 + 2x + 5} has and the correct option is "None". Drawing that
     * curve sitting clear of the axis lets the count be read straight off the picture with no
     * algebra - the answer, not a hint, and worse than no illustration at the defence. This shows
     * the three cases as a reference figure without saying which one she is looking at.
     *
     * <p>Found by a cold read: the no-answers-in-the-picture rule had been applied to the two
     * text diagrams and to none of the plots, and the javadoc claimed it as general.
     */
    private static void interceptCases(Graphics2D g) {
        double[] lifts = {-2.2, 0.0, 2.2};
        for (int panel = 0; panel < 3; panel++) {
            int left = 28 + panel * 148;
            miniParabola(g, left, 70, 128, 150, lifts[panel]);
        }
    }

    private static void miniParabola(Graphics2D g, int x, int y, int w, int h, double lift) {
        double xMin = -2.6;
        double xMax = 2.6;
        double yMin = -3.2;
        double yMax = 6.2;
        DoubleUnaryOperator sx = t -> x + (t - xMin) / (xMax - xMin) * w;
        DoubleUnaryOperator sy = t -> y + h - (t - yMin) / (yMax - yMin) * h;

        g.setColor(new Color(0xB6C2CF));
        g.setStroke(new BasicStroke(1f));
        g.draw(new java.awt.geom.Line2D.Double(x, sy.applyAsDouble(0), x + w, sy.applyAsDouble(0)));

        GeneralPath curve = new GeneralPath();
        boolean started = false;
        for (double t = xMin; t <= xMax + 1e-9; t += (xMax - xMin) / 200.0) {
            double value = t * t + lift;
            if (value < yMin || value > yMax) {
                started = false;
                continue;
            }
            double px = sx.applyAsDouble(t);
            double py = sy.applyAsDouble(value);
            if (started) {
                curve.lineTo(px, py);
            } else {
                curve.moveTo(px, py);
                started = true;
            }
        }
        g.setColor(CURVE);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(curve);
    }

    /**
     * "Which interface does HashMap implement?" - the implements arrow, drawn UML-style.
     *
     * <p><b>The interface box is "?" and not its name on purpose.</b> The correct answer to this
     * question is {@code Map}, so a labelled box would print the answer next to the question. An
     * illustration that answers its own multiple choice is worse than none at the defence. Same
     * reason as {@link #recursionDiagram}; found by looking at the output rather than by reading
     * the code that drew it.
     */
    private static void implementsDiagram(Graphics2D g) {
        box(g, 150, 44, 180, 56, "?");
        box(g, 150, 200, 180, 56, "HashMap");
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[] {7f, 6f}, 0f));
        g.draw(new java.awt.geom.Line2D.Double(240, 200, 240, 118));
        hollowArrowUp(g, 240, 100);
    }

    /**
     * "What does a recursive method need in order to terminate?" - calls narrowing to something.
     *
     * <p><b>The final box is "?" and not "base case".</b> That is the correct answer, and writing
     * it into the picture would answer the question for her. The diagram shows the shape - each
     * call smaller than the last, ending somewhere - and leaves the name to the options.
     */
    private static void recursionDiagram(Graphics2D g) {
        int y = 34;
        int width = 300;
        for (int level = 0; level < 4; level++) {
            box(g, 240 - width / 2, y, width, 42, level < 3 ? "f(n)" : null);
            if (level < 3) {
                g.setColor(INK);
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new java.awt.geom.Line2D.Double(240, y + 42, 240, y + 60));
                solidArrowDown(g, 240, y + 62);
            }
            y += 70;
            width -= 56;
        }
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(2.6f));
        g.draw(new Rectangle2D.Double(240 - (width + 56) / 2.0, y - 70, width + 56, 42));
        centred(g, "?", 240, y - 70 + 27, ACCENT);
    }

    /** "Which join returns every row of the left table?" - the canonical filled-left Venn. */
    private static void leftJoinDiagram(Graphics2D g) {
        Ellipse2D left = new Ellipse2D.Double(96, 70, 170, 170);
        Ellipse2D right = new Ellipse2D.Double(214, 70, 170, 170);
        java.awt.geom.Area filled = new java.awt.geom.Area(left);
        g.setColor(SHADE);
        g.fill(filled);
        g.setColor(INK);
        g.setStroke(new BasicStroke(2f));
        g.draw(left);
        g.draw(right);
        centred(g, "left", 150, 160, INK);
        centred(g, "right", 336, 160, INK);
    }

    /** "Removing a partial dependency on part of a composite key achieves:" - 2NF, drawn. */
    private static void partialDependencyDiagram(Graphics2D g) {
        box(g, 60, 60, 110, 50, "key A");
        box(g, 170, 60, 110, 50, "key B");
        box(g, 300, 190, 120, 50, "attr");
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(2f));
        g.draw(new java.awt.geom.Line2D.Double(225, 110, 340, 190));
        solidArrowDown(g, 344, 192);
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Rectangle2D.Double(54, 54, 232, 62));
    }

    // ------------------------------------------------------------- helpers

    private static void box(Graphics2D g, int x, int y, int w, int h, String label) {
        g.setColor(PAPER);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.8f));
        g.draw(new Rectangle2D.Double(x, y, w, h));
        if (label != null) {
            centred(g, label, x + w / 2, y + h / 2 + 6, INK);
        }
    }

    private static void centred(Graphics2D g, String text, int cx, int baseline, Color colour) {
        g.setColor(colour);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, cx - width / 2, baseline);
    }

    private static void hollowArrowUp(Graphics2D g, int x, int y) {
        GeneralPath head = new GeneralPath();
        head.moveTo(x, y);
        head.lineTo(x - 11, y + 18);
        head.lineTo(x + 11, y + 18);
        head.closePath();
        g.setColor(PAPER);
        g.fill(head);
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.8f));
        g.draw(head);
    }

    private static void solidArrowDown(Graphics2D g, int x, int y) {
        GeneralPath head = new GeneralPath();
        head.moveTo(x, y + 10);
        head.lineTo(x - 7, y - 4);
        head.lineTo(x + 7, y - 4);
        head.closePath();
        g.fill(head);
    }
}
