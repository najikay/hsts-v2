package common.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a deployment properties file is looked for before the bundled default
 * (E20.4).
 *
 * <p>Both tiers externalise one file each - {@code server.properties} beside the
 * server JAR, {@code client.properties} beside the client JAR - and both had the
 * same one-line rule: the file next to the JAR, or the working directory when
 * running from classes. That rule has an edge the demo machine can actually hit.
 * A shortcut, a service wrapper or a {@code java -jar C:\hsts\G13_Server.jar}
 * typed from {@code C:\Users\...} runs with a working directory that is not the
 * JAR's directory, and the operator who edited the {@code server.properties} they
 * were standing in would have watched the server ignore it and start on the
 * bundled defaults instead.
 *
 * <h2>The order</h2>
 * <ol>
 *   <li>beside the running JAR - the deployment layout the README and the
 *       submission zip both describe, and the one that must win when both exist;</li>
 *   <li>the working directory - the same file when the two directories differ,
 *       and the only candidate when running from an IDE or exploded classes;</li>
 *   <li>(the caller's job) the copy bundled in the JAR, then hard-coded defaults.</li>
 * </ol>
 *
 * <p>Beside-the-JAR first is deliberate. A file shipped next to the JAR was put
 * there for this installation; a file in whatever directory a shell happened to be
 * in is a weaker claim, and letting it override would make the same JAR behave
 * differently depending on where its shortcut was invoked from.
 *
 * <p>Kept here rather than in either config class because the rule is one rule.
 * Two copies of it drift, and a client that searches one list while the server
 * searches another is a support conversation nobody can have over the phone.
 */
public final class ExternalConfig {

    private ExternalConfig() {
    }

    /**
     * The ordered places an external config file may live.
     *
     * @param codeSourceLocation where the running code was loaded from: a JAR file
     *                           when packaged, a directory of classes otherwise
     * @param fileName           the file's simple name, e.g. {@code server.properties}
     * @return one or two candidates, most specific first, never empty and never
     *         containing the same file twice
     */
    public static List<Path> candidates(Path codeSourceLocation, String fileName) {
        List<Path> candidates = new ArrayList<>(2);
        if (codeSourceLocation != null && Files.isRegularFile(codeSourceLocation)) {
            Path besideJar = codeSourceLocation.getParent();
            if (besideJar != null) {
                candidates.add(besideJar.resolve(fileName));
            }
        }
        Path workingDirectory = Paths.get(fileName);
        if (!containsSamePath(candidates, workingDirectory)) {
            candidates.add(workingDirectory);
        }
        return List.copyOf(candidates);
    }

    /**
     * Picks the file to read out of {@link #candidates}.
     *
     * @param candidates the ordered candidates
     * @return the first candidate that exists as a regular file; when none does,
     *         the last candidate, so a caller can name a sensible path in its "no
     *         config found" line and still fall through to the classpath
     * @throws IllegalArgumentException if given no candidates at all
     */
    public static Path resolve(List<Path> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * {@link #resolve} over {@link #candidates}: the external file this run should
     * read, whether or not it exists.
     *
     * @param codeSourceLocation where the running code was loaded from
     * @param fileName           the file's simple name
     * @return the path to try before the bundled default
     */
    public static Path locate(Path codeSourceLocation, String fileName) {
        return resolve(candidates(codeSourceLocation, fileName));
    }

    /**
     * Same-file comparison that works for paths that do not exist yet, which is
     * most of them here: {@link Files#isSameFile} needs both ends to be real.
     */
    private static boolean containsSamePath(List<Path> paths, Path candidate) {
        Path normalised = candidate.toAbsolutePath().normalize();
        return paths.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(normalised::equals);
    }
}
