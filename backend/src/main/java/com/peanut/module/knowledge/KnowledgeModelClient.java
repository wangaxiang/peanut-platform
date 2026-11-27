package com.peanut.module.knowledge;

import static com.peanut.module.knowledge.KnowledgeModels.*;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class KnowledgeModelClient {
    private final RestClient client;
    private final ObjectMapper json;

    KnowledgeModelClient(@Value("${ai-service.base-url}") String url, ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(140));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.json = json;
    }

    long embed(ChunkRequest chunk) {
        Map<String, Object> body = Map.of("source_title", chunk.sourceTitle().strip(), "content", chunk.content().strip(),
                "metadata", chunk.metadata() == null ? json.createObjectNode() : chunk.metadata());
        EmbedResponse response = post("/embed", body, EmbedResponse.class);
        if (response == null || response.chunkId() == null || response.chunkId() <= 0) {
            throw unavailable("向量服务没有返回片段编号");
        }
        return response.chunkId();
    }

    ModelAnswer answer(Question request) {
        ObjectNode body = json.createObjectNode().put("question", request.question());
        if (request.fieldId() != null) {
            body.put("field_id", request.fieldId());
        }
        if (request.history() != null && !request.history().isEmpty()) {
            body.set("history", json.valueToTree(request.history()));
        }
        ModelAnswer response = post("/qa", body, ModelAnswer.class);
        if (response == null || response.answer() == null || response.answer().isBlank() || response.sources() == null) {
            throw unavailable("问答服务没有返回有效内容");
        }
        return response;
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return client.post().uri(path).body(body).retrieve().body(responseType);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "本地知识模型暂不可用，请稍后重试", error);
        }
    }

    private ResponseStatusException unavailable(String text) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, text);
    }
}
