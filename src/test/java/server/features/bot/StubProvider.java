package server.features.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * A provider whose answer a test decides (E16.8).
 *
 * <p>Used by the service tests, which are about guards and persistence rather than
 * about HTTP: they need a chain that answers, or does not, on demand. The adapters
 * themselves have their own tests against a loopback server and a stubbed SDK.
 */
final class StubProvider implements BotProvider {

    private final String name;
    private String answer;
    private BotProviderException.Kind failure;

    /** Every prompt this provider was handed, so a test can assert what the model saw. */
    final List<String> systemPrompts = new ArrayList<>();

    /** Every context block list this provider was handed. */
    final List<List<String>> contexts = new ArrayList<>();

    /** Every history this provider was handed. */
    final List<List<ChatTurn>> histories = new ArrayList<>();

    private StubProvider(String name) {
        this.name = name;
    }

    /** @return a provider that always answers with {@code answer}. */
    static StubProvider answering(String answer) {
        StubProvider provider = new StubProvider("deepseek");
        provider.answer = answer;
        return provider;
    }

    /** @return a provider that always fails, so the chain runs out of options. */
    static StubProvider failing() {
        StubProvider provider = new StubProvider("deepseek");
        provider.failure = BotProviderException.Kind.SERVER;
        return provider;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String ask(String systemPrompt, List<String> contextBlocks,
                      List<ChatTurn> history, String question) throws BotProviderException {
        systemPrompts.add(systemPrompt);
        contexts.add(List.copyOf(contextBlocks));
        histories.add(List.copyOf(history));
        if (failure != null) {
            throw new BotProviderException(name, failure, "scripted failure");
        }
        return answer;
    }

    /** @return the context the provider was given on the last ask. */
    List<String> lastContext() {
        return contexts.isEmpty() ? List.of() : contexts.get(contexts.size() - 1);
    }

    /** @return the history the provider was given on the last ask. */
    List<ChatTurn> lastHistory() {
        return histories.isEmpty() ? List.of() : histories.get(histories.size() - 1);
    }
}
