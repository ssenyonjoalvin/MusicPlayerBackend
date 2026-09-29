package com.pahappa.musicplayer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin configuration.
 *
 * <p>Native mobile clients do not enforce CORS, so this exists purely so a
 * browser-based dev client (or a future web front end) can talk to the API
 * during development. Production deployments should pin
 * {@code app.cors.allowed-origins} instead of using the {@code *} default.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Raises the async request timeout used by {@code StreamingResponseBody}.
     *
     * <p>Left at the container default (30s on Tomcat) a long track is cut off
     * mid-response and the player reports a corrupt file, even though every byte
     * on disk is fine. Ten minutes comfortably covers a slow mobile connection
     * serving a full-length song.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(properties.streaming().asyncTimeout().toMillis());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        AppProperties.Cors cors = properties.cors();
        if (cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
                .allowedMethods(cors.allowedMethods().toArray(String[]::new))
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
