package ro.alacrity.airbox.middleware.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mdw.grafana")
public record GrafanaProperties(
        String url,
        String publicUrl,
        String token,
        String username,
        String password,
        String templatesDir,
        String publicTokenSecret,
        String mapUid,
        String overviewUid,
        String stationUid,
        Sync sync
) {
    public record Sync(boolean enabled, String cron) {}
}
