package com.cezar.newmiddleware.configs;

import com.cezar.newmiddleware.grafana.DashboardSyncJob;
import com.cezar.newmiddleware.grafana.DashboardTemplateService;
import com.cezar.newmiddleware.grafana.GrafanaClient;
import com.cezar.newmiddleware.repository.GrafanaArtifactRepository;
import com.cezar.newmiddleware.repository.InstallationsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "mdw.grafana.sync.enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(GrafanaProperties.class)
public class GrafanaSyncConfig {

    @Bean
    RestClient grafanaRestClient(GrafanaProperties grafanaProperties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(grafanaProperties.url())
                .requestFactory(requestFactory);

        // If the access token is provided then pass it forward to check if it truly exists (the credentials are not mandatory since the key is what validates)
        if(grafanaProperties.token() != null && !grafanaProperties.token().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                    "Bearer " + grafanaProperties.token());

        // Otherwise try using the provided credentials
        } else {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                    "Basic " + HttpHeaders.encodeBasicAuth(grafanaProperties.username(), grafanaProperties.password(), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    @Bean
    GrafanaClient grafanaClient(RestClient grafanaRestClient) {
        return new GrafanaClient(grafanaRestClient);
    }

    @Bean
    DashboardTemplateService dashboardTemplateService(GrafanaProperties grafanaProperties) {
        return new DashboardTemplateService(grafanaProperties.templatesDir());
    }

    @Bean
    DashboardSyncJob dashboardSyncJob(GrafanaClient grafanaClient, DashboardTemplateService dashboardTemplateService,
                                      InstallationsRepository installationsRepository, GrafanaArtifactRepository grafanaArtifactRepository,
                                      GrafanaProperties grafanaProperties) {
        if(grafanaProperties.publicTokenSecret() == null || grafanaProperties.publicTokenSecret().isBlank()) {
            throw new IllegalStateException("mdw.grafana.public-token-secret must be set when mdw.grafana.sync.enabled=true");
        }

        return new DashboardSyncJob(grafanaClient, dashboardTemplateService, installationsRepository, grafanaArtifactRepository, grafanaProperties);
    }
}
