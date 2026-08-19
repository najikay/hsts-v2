package client.core;

import java.util.Properties;

/**
 * Persistence seam for small key/value client preferences (Presentation tier, E4.7).
 *
 * <p>Theme choice (mode + accent palette) and the last successfully-used server
 * endpoint both need to survive a restart, and both need to be unit-testable
 * without touching the developer's home directory. Everything that persists
 * preferences therefore depends on this interface, never on {@code java.nio}
 * directly: production wires {@link FilePropertiesStore}, tests wire
 * {@link InMemoryPropertiesStore}.
 *
 * <p>Implementations are expected to be forgiving — a missing, unreadable or
 * corrupt store returns empty properties rather than throwing, because a
 * damaged preferences file must never stop the client from starting.
 */
public interface PropertiesStore {

    /** @return the persisted properties; empty (never {@code null}) when absent or unreadable. */
    Properties load();

    /** Persists the given properties, replacing anything stored before. */
    void save(Properties properties);
}
