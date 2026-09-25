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

    /** The status, and the body as JSON, missing when there is none or it is not JSON. */
    record Reply(int status, JsonNode body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Reply get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    Reply post(String url, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
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
        return new Reply(response.statusCode(), body);
    }
}
