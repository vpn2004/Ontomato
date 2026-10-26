package io.ontomato.dataengine.dataAdapter;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Only schema/instance reads and the native read-query endpoint are exposed. */
final class OnnCoreHttpClient {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String configuredUrl;
    private final Duration timeout;
    private final int maxBytes;

    OnnCoreHttpClient(String url) { this(url, Duration.ofSeconds(12), MAX_BYTES); }

    OnnCoreHttpClient(String url, Duration timeout, int maxBytes) {
        if (timeout == null || timeout.toMillis() < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid ONN read limits");
        this.configuredUrl = url; this.timeout = timeout; this.maxBytes = maxBytes;
    }

    String baseUrl() {
        if (configuredUrl == null || configuredUrl.isBlank()) throw new IllegalArgumentException("Configure the ONN Core service URL first");
        URI uri = URI.create(configuredUrl.trim());
        String path = uri.getPath();
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(path == null || path.isEmpty() || path.equals("/") || path.equals("/api/v1") || path.equals("/api/v1/"))) {
            throw new IllegalArgumentException("ONN Core URL must be an HTTP(S) origin or end in /api/v1, without credentials or query parameters");
        }
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/api/v1";
    }

    JSONObject schema() throws Exception { return request("GET", "/schema/all", null); }
    JSONObject sample(String className) throws Exception {
        return request("GET", "/data/sample?class=" + encode(className) + "&limit=1", null);
    }
    JSONObject instance(String className, String id) throws Exception {
        try { return request("GET", "/data/instance?class_path=" + encode(className) + "&id=" + encode(id), null); }
        catch (OnnHttpException e) { if (e.status == 404) return null; throw e; }
    }
    JSONObject query(JSONObject query) throws Exception { return request("POST", "/query/execute", JSON.toJSONString(query)); }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private JSONObject request(String method, String route, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + route))
            .timeout(timeout).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        // The deadline includes body consumption. An InputStream body handler completes
        // after headers and would allow a stalled response body to block indefinitely.
        CompletableFuture<HttpResponse<byte[]>> pending = CLIENT.sendAsync(builder.build(), info -> new BoundedBodySubscriber(maxBytes));
        HttpResponse<byte[]> response;
        try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("ONN read exceeded its response deadline");
        } catch (InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw e; }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("ONN response failed", e.getCause());
        }
        byte[] bytes = response.body();
        if (response.statusCode() != 200) throw new OnnHttpException(response.statusCode(), method + " " + route.split("\\?")[0]);
        JSONObject result;
        try { result = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new IllegalStateException("ONN returned an invalid JSON response", e); }
        if (result == null || Boolean.TRUE.equals(result.getBoolean("degraded"))
            || Boolean.FALSE.equals(result.getBoolean("success")) || result.containsKey("error")
            || Boolean.TRUE.equals(result.getBoolean("partial")) || Boolean.TRUE.equals(result.getBoolean("truncated"))
            || Boolean.FALSE.equals(result.getBoolean("complete"))
            || (route.startsWith("/query/") && Boolean.TRUE.equals(result.getBoolean("has_more")))
            || Set.of("failed", "failure", "error", "invalid", "degraded", "partial", "truncated", "timeout", "cancelled", "canceled")
                .contains(result.getString("status") == null ? "" : result.getString("status").toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("ONN returned a failed, degraded or incomplete response; no answer was produced");
        }
        return result;
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        BoundedBodySubscriber(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; subscription.request(Long.MAX_VALUE);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            long nextSize = bytes.size();
            for (ByteBuffer buffer : buffers) nextSize += buffer.remaining();
            if (nextSize > limit) {
                subscription.cancel();
                body.completeExceptionally(new IllegalStateException("ONN response exceeds the read limit; query was not accepted as complete"));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    static final class OnnHttpException extends IllegalStateException {
        final int status;
        OnnHttpException(int status, String operation) { super("ONN " + operation + " failed (HTTP " + status + ")"); this.status = status; }
    }
}
