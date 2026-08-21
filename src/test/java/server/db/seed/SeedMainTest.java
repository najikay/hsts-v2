package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command line's argument parsing and its confirmation (E2.15).
 *
 * <p>{@link SeedMain#main} itself is not tested here: it migrates a real database and calls
 * {@code System.exit}, so exercising it would need a live server and a security manager to
 * survive. What is tested is everything that decides <em>whether the database gets emptied</em>,
 * which is the part where being wrong is expensive.
 */
class SeedMainTest {

    @Test
    @DisplayName("no flags means load-if-missing, which destroys nothing")
    void defaultModeIsSafe() {
        assertThat(SeedMain.modeFor()).isEqualTo(SeedMode.LOAD_IF_MISSING);
        assertThat(SeedMain.assumesYes()).isFalse();
    }

    @Test
    @DisplayName("--reseed selects the destructive mode, in any case")
    void reseedIsRecognised() {
        assertThat(SeedMain.modeFor("--reseed")).isEqualTo(SeedMode.RESEED);
        assertThat(SeedMain.modeFor("--RESEED")).isEqualTo(SeedMode.RESEED);
        assertThat(SeedMain.modeFor("--yes")).isEqualTo(SeedMode.LOAD_IF_MISSING);
    }

    @Test
    @DisplayName("--yes waives the confirmation only when it is actually given")
    void assumeYesIsRecognised() {
        assertThat(SeedMain.assumesYes("--reseed", "--yes")).isTrue();
        assertThat(SeedMain.assumesYes("--reseed")).isFalse();
        // Not a prefix match: --yesterday is not consent.
        assertThat(SeedMain.assumesYes("--yesterday")).isFalse();
    }

    @Test
    @DisplayName("only the word yes is a yes")
    void onlyYesConfirms() {
        assertThat(SeedMain.readsYes(reader("yes"))).isTrue();
        assertThat(SeedMain.readsYes(reader("YES"))).isTrue();
        assertThat(SeedMain.readsYes(reader("  yes  "))).isTrue();

        assertThat(SeedMain.readsYes(reader("y"))).isFalse();
        assertThat(SeedMain.readsYes(reader("no"))).isFalse();
        assertThat(SeedMain.readsYes(reader(""))).isFalse();
    }

    @Test
    @DisplayName("end of stream is a refusal, never a yes")
    void noConsoleMeansNo() {
        // The one that matters. A null line means nothing is attached to stdin, so nobody is
        // there to consent. Reading that as agreement is how a scripted run in the wrong
        // terminal empties a database; a caller that means it passes --yes and never gets here.
        assertThat(SeedMain.readsYes(new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() {
                return null;
            }
        })).isFalse();
    }

    @Test
    @DisplayName("an unreadable console is a refusal too")
    void anIoErrorMeansNo() {
        assertThat(SeedMain.readsYes(new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("console went away");
            }
        })).isFalse();
    }

    private static BufferedReader reader(String answer) {
        return new BufferedReader(new StringReader(answer));
    }
}
