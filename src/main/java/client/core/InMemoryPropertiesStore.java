package client.core;

import java.util.Properties;

/**
 * Non-persistent {@link PropertiesStore} backed by a field (Presentation tier).
 *
 * <p>Two production-adjacent uses beyond unit tests: the {@code --gallery} dev
 * screen runs on one of these so playing with themes there never rewrites the
 * developer's real preferences, and any future "incognito"/kiosk mode gets a
 * no-op persistence layer for free.
 *
 * <p>Copies are taken on the way in and on the way out, so a caller mutating the
 * {@link Properties} it handed over (or got back) cannot corrupt the store.
 */
public final class InMemoryPropertiesStore implements PropertiesStore {

    private final Properties data = new Properties();

    /** Creates an empty store. */
    public InMemoryPropertiesStore() {
    }

    /** Creates a store pre-seeded with {@code initial} (copied). */
    public InMemoryPropertiesStore(Properties initial) {
        if (initial != null) {
            data.putAll(initial);
        }
    }

    @Override
    public Properties load() {
        Properties copy = new Properties();
        copy.putAll(data);
        return copy;
    }

    @Override
    public void save(Properties properties) {
        data.clear();
        if (properties != null) {
            data.putAll(properties);
        }
    }

    /** @return {@code true} when nothing has been stored yet. */
    public boolean isEmpty() {
        return data.isEmpty();
    }
}
