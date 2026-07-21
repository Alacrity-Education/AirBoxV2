package ro.alacrity.airbox.middleware.controller;

import ro.alacrity.airbox.middleware.service.SensorReadingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
public class SensorReadingController {

    private final SensorReadingService sensorReadingService;

    public SensorReadingController(SensorReadingService sensorReadingService) {
        this.sensorReadingService = sensorReadingService;
    }

    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void submit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "ApiKey", required = false) String apiKeyHeader,
            @RequestHeader(value = "X-ApiKey", required = false) String xApiKey,
            @RequestBody byte[] rawBody) {

        sensorReadingService.ingest(authorization, apiKeyHeader, xApiKey, rawBody);
    }
}
