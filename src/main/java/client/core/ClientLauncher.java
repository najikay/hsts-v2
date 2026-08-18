package client.core;

/**
 * Non-JavaFX entry point for the client Fat JAR (manifest {@code Main-Class}).
 *
 * <p>JavaFX {@link Application} subclasses cannot be the shaded-jar manifest entry
 * point, so this plain class delegates to {@link ClientApp#main(String[])}.
 */
public final class ClientLauncher {

    private ClientLauncher() {}

    public static void main(String[] args) {
        ClientApp.main(args);
    }
}
