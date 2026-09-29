package org.springframework.web.servlet.config.annotation;

/**
 * Test-only view of {@link AsyncSupportConfigurer}'s protected timeout.
 *
 * <p>Lives in Spring's package purely so it can read {@code getTimeout()}, which
 * is the value the framework will actually apply. Asserting on a getter of our
 * own would only prove the number round-trips, not that Spring is configured
 * with it.
 */
public class AsyncSupportConfigurerProbe extends AsyncSupportConfigurer {

    /** The configured default async timeout in milliseconds, or null if unset. */
    public Long timeoutMillis() {
        return getTimeout();
    }
}
