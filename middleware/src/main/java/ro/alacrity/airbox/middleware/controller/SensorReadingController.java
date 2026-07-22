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

    /**
     * Public help page. The wiki specifies that a GET to the ingest endpoint should render a
     * small self-contained HTML guide (no auth, no runtime data) explaining how to POST readings
     * and linking to the full documentation. Kept static so it never depends on request state.
     */
    @GetMapping(value = "/submit", produces = "text/html;charset=utf-8")
    public String submitHelp() {
        return HELP_PAGE;
    }

    private static final String HELP_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>AirBox Ingest API</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0; padding: 2rem 1rem;
                  font: 16px/1.6 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
                  background: #f5f7fa; color: #1a2027;
                }
                main {
                  max-width: 720px; margin: 0 auto;
                  background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
                  padding: 1.5rem 2rem 2rem;
                  box-shadow: 0 1px 3px rgba(0,0,0,.06);
                }
                h1 { margin: .2rem 0 .3rem; font-size: 1.7rem; }
                h2 { margin: 1.6rem 0 .5rem; font-size: 1.15rem; color: #0f766e; }
                p.lead { color: #475569; margin-top: 0; }
                code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
                code { background: #eef2f7; padding: .1rem .35rem; border-radius: 5px; font-size: .9em; }
                pre {
                  background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 8px;
                  overflow-x: auto; font-size: .85rem; line-height: 1.5;
                }
                table { border-collapse: collapse; width: 100%; margin: .5rem 0; font-size: .92rem; }
                th, td { text-align: left; padding: .4rem .6rem; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
                th { color: #334155; }
                ul { margin: .4rem 0; padding-left: 1.3rem; }
                .req { color: #b91c1c; font-weight: 600; }
                a { color: #0f766e; }
                footer { margin-top: 1.8rem; font-size: .95rem; }
                @media (prefers-color-scheme: dark) {
                  body { background: #0b1220; color: #dbe4ee; }
                  main { background: #111a2b; border-color: #1e293b; box-shadow: none; }
                  h2 { color: #2dd4bf; }
                  p.lead { color: #94a3b8; }
                  code { background: #1e293b; color: #dbe4ee; }
                  th { color: #cbd5e1; }
                  th, td { border-bottom-color: #1e293b; }
                  a { color: #2dd4bf; }
                }
              </style>
            </head>
            <body>
            <main>
              <h1>AirBox Ingest API</h1>
              <p class="lead">Send your air-quality sensor readings to AirBox. This page is a quick
                how-to; the full reference lives in the documentation linked at the bottom.</p>

              <h2>How to submit</h2>
              <p>Send an HTTP <strong>POST</strong> request to:</p>
              <pre>POST https://ingest.airbox.alacrity.ro/api/v2/submit</pre>
              <p>with header <code>Content-Type: application/json</code> and your API key supplied in
                <strong>any one</strong> of these three headers:</p>
              <ul>
                <li><code>Authorization: ApiKey &lt;key&gt;</code></li>
                <li><code>ApiKey: &lt;key&gt;</code></li>
                <li><code>X-ApiKey: &lt;key&gt;</code></li>
              </ul>

              <h2>Request body</h2>
              <p>A single JSON object. Required fields:</p>
              <table>
                <tr><th>Field</th><th>Type</th><th>Notes</th></tr>
                <tr><td><code>geohash</code> <span class="req">required</span></td><td>string</td><td>&le; 100 characters</td></tr>
                <tr><td><code>charge</code> <span class="req">required</span></td><td>float</td><td>0&ndash;100</td></tr>
                <tr><td><code>sun</code> <span class="req">required</span></td><td>boolean</td><td>true / false</td></tr>
              </table>
              <p>Optional numeric fields &mdash; omit any your sensor does not measure:</p>
              <ul>
                <li><code>co2</code>, <code>pm1</code>, <code>pm25</code>, <code>pm4</code>, <code>pm10</code></li>
                <li><code>temp</code>, <code>hum</code></li>
                <li><code>voc_index</code>, <code>nox_index</code>, <code>voc</code>, <code>nox</code></li>
              </ul>
              <p>Rules: string fields are limited to <strong>100 characters</strong>, <strong>no extra
                fields</strong> are allowed, and invalid payloads are <strong>dropped with HTTP 400</strong>.</p>

              <h2>Example</h2>
              <pre>curl -X POST https://ingest.airbox.alacrity.ro/api/v2/submit \\
              -H "Content-Type: application/json" \\
              -H "Authorization: ApiKey &lt;your-key&gt;" \\
              -d '{"geohash":"u0nzj8","charge":87.5,"sun":true,"pm25":12.3,"temp":21.4,"hum":48.0}'</pre>

              <footer>
                <a href="https://wiki.alacrity.ro/en/Projects/airbox-v2">Full documentation</a>
              </footer>
            </main>
            </body>
            </html>
            """;
}
