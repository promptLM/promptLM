/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.promptlm.test.remotes;

import dev.promptlm.testutils.gitea.GiteaContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Read-only witness over a Gitea repository's REST API.
 *
 * <p>Used by acceptance tests to confirm that the native binary actually
 * pushed a prompt YAML to the remote — without touching the application's
 * own REST surface. All methods are pure observers.
 *
 * <p>The implementation hits the standard Gitea REST endpoints
 * ({@code /api/v1/repos/{owner}/{repo}/contents/...} and
 * {@code /api/v1/repos/{owner}/{repo}/commits}) using the container's admin
 * token for auth. Error responses surface the response body in assertion
 * messages so flaky pushes are diagnosable.
 */
public final class GiteaContentsClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final String apiBaseUrl;
    private final String token;

    /**
     * Creates a contents client that authenticates against {@code gitea} as
     * the container's admin user.
     */
    public GiteaContentsClient(GiteaContainer gitea) {
        this(gitea.getApiUrl(), gitea.getAdminToken());
    }

    /**
     * Creates a contents client bound to the given Gitea API base URL.
     *
     * @param apiBaseUrl base URL ending in {@code /api/v1} (no trailing slash)
     * @param token      Gitea personal access token used for {@code Authorization}
     */
    public GiteaContentsClient(String apiBaseUrl, String token) {
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
        this.token = token;
    }

    /**
     * Returns the blob SHA for a file at the given ref. Throws when the file
     * is missing or the API surfaces a non-2xx response.
     */
    public String fileSha(String owner, String repo, String ref, String path) {
        String url = apiBaseUrl + "/repos/" + owner + "/" + repo + "/contents/" + encodePath(path)
                + "?ref=" + encode(ref);
        HttpResponse<String> response = get(url);
        if (response.statusCode() != 200) {
            throw new AssertionError("fileSha lookup failed for " + url
                    + " — status " + response.statusCode() + ", body=" + response.body());
        }
        return extractJsonString(response.body(), "sha");
    }

    /**
     * Returns the commit SHA of the most recent commit that touched the
     * given path on the repository's default branch.
     */
    public String topCommitSha(String owner, String repo, String path) {
        String url = apiBaseUrl + "/repos/" + owner + "/" + repo + "/commits"
                + "?path=" + encode(path) + "&limit=1";
        HttpResponse<String> response = get(url);
        if (response.statusCode() != 200) {
            throw new AssertionError("topCommitSha lookup failed for " + url
                    + " — status " + response.statusCode() + ", body=" + response.body());
        }
        return extractJsonString(response.body(), "sha");
    }

    /**
     * Returns the head commit SHA of a named branch.
     */
    public String branchHeadSha(String owner, String repo, String branch) {
        String url = apiBaseUrl + "/repos/" + owner + "/" + repo + "/branches/" + encode(branch);
        HttpResponse<String> response = get(url);
        if (response.statusCode() != 200) {
            throw new AssertionError("branchHeadSha lookup failed for " + url
                    + " — status " + response.statusCode() + ", body=" + response.body());
        }
        // The Gitea branch payload includes a nested "commit": { "id": "..." }
        String body = response.body();
        int commitIdx = body.indexOf("\"commit\"");
        if (commitIdx < 0) {
            throw new AssertionError("branchHeadSha response missing 'commit' for " + url + ": " + body);
        }
        return extractJsonString(body.substring(commitIdx), "id");
    }

    /**
     * Polls until {@code path} appears at {@code ref} or {@code timeout}
     * elapses. Surfaces the last-seen body when the wait times out.
     */
    public void awaitPath(String owner, String repo, String ref, String path, Duration timeout) {
        AtomicReference<String> lastBody = new AtomicReference<>("<no response yet>");
        AtomicReference<Integer> lastStatus = new AtomicReference<>(-1);
        await().atMost(timeout).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            String url = apiBaseUrl + "/repos/" + owner + "/" + repo + "/contents/" + encodePath(path)
                    + "?ref=" + encode(ref);
            HttpResponse<String> response = get(url);
            lastBody.set(response.body());
            lastStatus.set(response.statusCode());
            assertThat(response.statusCode())
                    .as("Gitea contents probe for %s — last status %s, body=%s",
                            url, lastStatus.get(), lastBody.get())
                    .isEqualTo(200);
        });
    }

    private HttpResponse<String> get(String url) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (token != null && !token.isBlank()) {
            requestBuilder.header("Authorization", "token " + token);
        }
        try {
            return http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (Exception e) {
            throw new AssertionError("Gitea API request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private static String encodePath(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        StringBuilder sb = new StringBuilder(normalized.length() + 8);
        for (String segment : normalized.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(encode(segment));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String extractJsonString(String body, String key) {
        String marker = "\"" + key + "\"";
        int idx = body.indexOf(marker);
        if (idx < 0) {
            throw new AssertionError("JSON missing key '" + key + "': " + body);
        }
        int colon = body.indexOf(':', idx + marker.length());
        if (colon < 0) {
            throw new AssertionError("JSON malformed near key '" + key + "': " + body);
        }
        int quoteStart = body.indexOf('"', colon + 1);
        if (quoteStart < 0) {
            throw new AssertionError("JSON value for key '" + key + "' not a string: " + body);
        }
        int quoteEnd = body.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            throw new AssertionError("JSON value for key '" + key + "' unterminated: " + body);
        }
        return body.substring(quoteStart + 1, quoteEnd);
    }
}
