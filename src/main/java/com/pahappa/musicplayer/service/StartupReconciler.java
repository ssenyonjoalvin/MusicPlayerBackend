package com.pahappa.musicplayer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reconciles download state on boot.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than during context refresh so
 * the HTTP port is already accepting traffic and a slow reconciliation cannot
 * delay startup.
 */
@Component
public class StartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final DownloadService downloadService;

    public StartupReconciler(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int stranded = downloadService.reconcileOnStartup();
            log.info("Startup reconciliation complete: {} interrupted job(s) marked failed", stranded);
        } catch (RuntimeException ex) {
            // Never let housekeeping stop the API from serving the library.
            log.error("Startup reconciliation failed", ex);
        }
    }
}
