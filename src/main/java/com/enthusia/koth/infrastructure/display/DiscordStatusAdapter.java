package com.enthusia.koth.infrastructure.display;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.domain.event.ActiveEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiscordStatusAdapter implements AnnouncementPort {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String KOTH_PREFIX = "KOTH ";

    private final ConfigurationService config;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Display adapter holds shared configuration and logger services.")
    public DiscordStatusAdapter(ConfigurationService config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void announceStarting(ActiveEvent event) {
        send(KOTH_PREFIX + event.request().family().key() + " starts at " + event.startsAt() + ".");
    }

    @Override
    public void announceStarted(ActiveEvent event) {
        send(KOTH_PREFIX + event.request().family().key() + " is active.");
    }

    @Override
    public void announceProgress(ActiveEvent event) {
        event.currentController()
                .ifPresent(controller -> send(KOTH_PREFIX + event.request().family().key()
                        + " controller: " + controller.storageKey()));
    }

    @Override
    public void announceEnded(ActiveEvent event, Optional<String> winner) {
        String result = winner.map(value -> "Winner: " + value).orElse("No winner.");
        send(KOTH_PREFIX + event.request().family().key() + " ended. " + result);
    }

    private void send(String content) {
        if (!config.settings().discordEnabled()) {
            return;
        }
        String webhookUrl = config.settings().discordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + escapeJson(content) + "\"}"))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Failed to send KOTH Discord update.", error);
                    return null;
                });
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
