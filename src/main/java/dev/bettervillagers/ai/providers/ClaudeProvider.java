package dev.bettervillagers.ai.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bettervillagers.ai.AIException;
import dev.bettervillagers.ai.AIProvider;
import dev.bettervillagers.ai.AIRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Anthropic Claude 供应商（规范 1.1）。
 * <p>
 * 使用 Anthropic Messages API：system 作为顶层字段，请求头携带 x-api-key 与 anthropic-version。
 */
public class ClaudeProvider implements AIProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final HttpClient httpClient;

    public ClaudeProvider(String endpoint, String apiKey, String defaultModel) {
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public String completeBlocking(AIRequest req) throws AIException {
        String model = (req.model() == null || req.model().isBlank()) ? defaultModel : req.model();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", req.maxTokens());
        body.addProperty("temperature", req.temperature());

        JsonArray messages = new JsonArray();
        String systemText = null;
        for (AIRequest.Message m : req.messages()) {
            if ("system".equals(m.role())) {
                systemText = m.content();
            } else {
                JsonObject o = new JsonObject();
                o.addProperty("role", m.role());
                o.addProperty("content", m.content());
                messages.add(o);
            }
        }
        if (systemText != null) {
            body.addProperty("system", systemText);
        }
        body.add("messages", messages);

        String url = endpoint.replaceAll("/+$", "") + "/messages";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, req.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("x-api-key", apiKey == null ? "" : apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code == 429) {
                throw new AIException(dev.bettervillagers.BV.messages().raw("errors.ai-rate-limit"), null, true);
            }
            if (code < 200 || code >= 300) {
                throw new AIException(dev.bettervillagers.BV.messages().raw("errors.ai-http-error")
                        .replace("{code}", String.valueOf(code)).replace("{body}", truncate(resp.body())), null, code >= 500);
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            // 越界防护：异常响应体可能缺少 content 字段或为空数组
            var contentArr = json.getAsJsonArray("content");
            if (contentArr == null || contentArr.isEmpty()) {
                throw new AIException(dev.bettervillagers.BV.messages().raw("errors.ai-http-error")
                        .replace("{code}", String.valueOf(code)).replace("{body}", truncate(resp.body())), null, false);
            }
            return contentArr.get(0).getAsJsonObject().get("text").getAsString();
        } catch (AIException e) {
            throw e;
        } catch (Exception e) {
            throw new AIException(dev.bettervillagers.BV.messages().raw("log.provider-call-fail")
                    .replace("{provider}", "claude").replace("{error}", e.getMessage()), e, true);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
