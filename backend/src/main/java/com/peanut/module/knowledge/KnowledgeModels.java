package com.peanut.module.knowledge;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class KnowledgeModels {
    private KnowledgeModels() {
    }

    record ChunkRequest(@NotBlank @Size(max = 200) String sourceTitle,
                        @NotBlank @Size(max = 5000) String content, JsonNode metadata) {
    }

    record Chunk(long id, String sourceTitle, String content, JsonNode metadata, OffsetDateTime createdAt) {
    }

    record ImportResult(int successCount, int failureCount, List<Long> chunkIds, List<ImportFailure> failures) {
    }

    record ImportFailure(long row, String message) {
    }

    record PriorTurn(@NotBlank @Size(max = 2000) String question, @NotBlank @Size(max = 3000) String answer) {
    }

    record Question(@NotBlank @Size(max = 2000) String question, @Positive Long fieldId,
                    @Size(max = 3) List<@NotNull @Valid PriorTurn> history) {
    }

    record Source(long chunkId, String sourceTitle) {
    }

    record Answer(long id, String question, String answer, Long fieldId, List<Source> sources,
                  OffsetDateTime createdAt) {
    }

    record LoggedAnswer(long id, String question, String answer, Long fieldId, List<Long> sourceIds,
                        OffsetDateTime createdAt) {
        Answer withSources(Map<Long, String> titles) {
            return new Answer(id, question, answer, fieldId,
                    sourceIds.stream().map(chunkId -> new Source(chunkId,
                            titles.getOrDefault(chunkId, "资料 #" + chunkId))).toList(), createdAt);
        }
    }

    record EmbedResponse(@JsonProperty("chunk_id") Long chunkId) {
    }

    record ModelAnswer(String answer, List<Long> sources) {
    }
}
