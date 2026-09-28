package com.match3d.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/** JSON over HTTP, as any client of the system would send it. */
final class Http {

    /**
     * The status, the body as JSON (missing when there is none or it is not
     * JSON), and the name of the intake copy that served it, from the
     * X-Intake-Pod header, empty when the header is absent.
     */
    record Reply(int status, JsonNode body, String pod) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Reply get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    /**
     * A GET over a connection of its own, closed after. kube-proxy spreads a
     * Service's traffic per connection, not per request, so reaching every copy
     * behind it means opening a fresh connection each time; a pooled client
     * would keep hitting whichever copy it first connected to.
     */
    Reply freshGet(String url) throws IOException, InterruptedException {
        HttpClient once = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = once.send(request, HttpResponse.BodyHandlers.ofString());
        String pod = response.headers().firstValue("X-Intake-Pod").orElse("");
        return new Reply(response.statusCode(), MissingNode.getInstance(), pod);
    }

    Reply post(String url, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /**
     * A POST over a connection of its own, so concurrent calls can land on
     * different copies behind the Service rather than sharing one connection.
     */
    Reply freshPost(String url, Object body) throws IOException, InterruptedException {
        HttpClient once = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = once.send(request, HttpResponse.BodyHandlers.ofString());
        String pod = response.headers().firstValue("X-Intake-Pod").orElse("");
        return new Reply(response.statusCode(), MissingNode.getInstance(), pod);
    }

    private Reply send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request.timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body;
        try {
            body = response.body().isEmpty() ? MissingNode.getInstance() : JSON.readTree(response.body());
        } catch (IOException notJson) {
            body = MissingNode.getInstance();
        }
        String pod = response.headers().firstValue("X-Intake-Pod").orElse("");
        return new Reply(response.statusCode(), body, pod);
    }
}
