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
 * OpenAI 兼容协议供应商（规范 1.1）。
 * <p>
 * 覆盖 OpenAI / DeepSeek / 智谱GLM / Moonshot Kimi / 自定义端点，
 * 各者仅在默认 endpoint 与模型上不同。POST /chat/completions，Bearer 鉴权。
 */
public class OpenAICompatibleProvider implements AIProvider {

    private final String id;
    private final String endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final HttpClient httpClient;

    public OpenAICompatibleProvider(String id, String endpoint, String apiKey, String defaultModel) {
        this.id = id;
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? defaultEndpoint(id) : endpoint;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String completeBlocking(AIRequest req) throws AIException {
        String model = (req.model() == null || req.model().isBlank()) ? defaultModel : req.model();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", req.temperature());
        body.addProperty("max_tokens", req.maxTokens());
        JsonArray messages = new JsonArray();
        for (AIRequest.Message m : req.messages()) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            messages.add(o);
        }
        body.add("messages", messages);

        String url = endpoint.replaceAll("/+$", "") + "/chat/completions";
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, req.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code == 429) {
                throw new AIException(dev.bettervillagers.BV.messages().raw("errors.ai-rate-limit"), null, true);
            }
            if (code < 200 || code >= 300) {
                throw new AIException(dev.bettervillagers.BV.messages().raw("errors.ai-http-error")
                        .replace("{code}", String.valueOf(code)).replace("{body}", truncate(resp.body())), null, code >= 500);
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            // 越界防护：异常响应体可能缺少 choices 字段或为空数组
            var choicesArr = json.getAsJsonArray("choices");
            if (choicesArr == null || choicesArr.isEmpty()) {
                throw new AIException(dev.bettervillagers.BV.messages().raw("errors.ai-http-error")
                        .replace("{code}", String.valueOf(code)).replace("{body}", truncate(resp.body())), null, false);
            }
            return choicesArr.get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (AIException e) {
            throw e;
        } catch (Exception e) {
            throw new AIException(dev.bettervillagers.BV.messages().raw("log.provider-call-fail")
                    .replace("{provider}", id).replace("{error}", e.getMessage()), e, true);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** 各供应商默认端点（未配置 api-endpoint 时使用）。 */
    private static String defaultEndpoint(String id) {
        return switch (id) {
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            case "kimi" -> "https://api.moonshot.cn/v1";
            case "custom" -> "https://api.example.com/v1";
            default -> "https://api.openai.com/v1";
        };
    }
}
