package com.peanut.module.knowledge;

import static com.peanut.module.knowledge.KnowledgeModels.*;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class KnowledgeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    KnowledgeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Page<Chunk> chunks(Pageable page) {
        Map<String, Object> parameters = Map.of("limit", page.getPageSize(), "offset", page.getOffset());
        List<Chunk> chunks = jdbc.query("""
                SELECT id, source_title, content, metadata, created_at FROM knowledge_chunk
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """, parameters, (row, index) -> new Chunk(row.getLong("id"), row.getString("source_title"),
                row.getString("content"), readMetadata(row.getString("metadata")),
                row.getObject("created_at", OffsetDateTime.class)));
        long count = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Map.of(), Long.class);
        return new PageImpl<>(chunks, page, count);
    }

    boolean fieldExists(long fieldId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM field WHERE id = :id)",
                Map.of("id", fieldId), Boolean.class));
    }

    LoggedAnswer saveAnswer(Question request, String answer, List<Long> sources) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("fieldId", request.fieldId(), Types.BIGINT).addValue("question", request.question())
                .addValue("answer", answer).addValue("sources", sources.toString());
        return jdbc.queryForObject("""
                INSERT INTO qa_log (question, answer, field_id, sources)
                VALUES (:question, :answer, :fieldId, CAST(:sources AS jsonb))
                RETURNING id, question, answer, field_id, sources, created_at
                """, parameters, this::mapAnswer);
    }

    Page<LoggedAnswer> history(Long fieldId, Pageable page) {
        String condition = fieldId == null ? "" : " WHERE field_id = :fieldId ";
        MapSqlParameterSource parameters = new MapSqlParameterSource("fieldId", fieldId)
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<LoggedAnswer> answers = jdbc.query("""
                SELECT id, question, answer, field_id, sources, created_at FROM qa_log
                """ + condition + """
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """, parameters, this::mapAnswer);
        long count = jdbc.queryForObject("SELECT count(*) FROM qa_log" + condition, parameters, Long.class);
        return new PageImpl<>(answers, page, count);
    }

    Map<Long, String> titles(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT id, source_title FROM knowledge_chunk WHERE id IN (:ids)", Map.of("ids", ids),
                result -> {
                    Map<Long, String> titles = new HashMap<>();
                    while (result.next()) {
                        long id = result.getLong("id");
                        String title = result.getString("source_title");
                        titles.put(id, title == null || title.isBlank() ? "资料 #" + id : title);
                    }
                    return titles;
                });
    }

    private LoggedAnswer mapAnswer(ResultSet row, int index) throws SQLException {
        String sourceJson = row.getString("sources");
        List<Long> sourceIds;
        try {
            sourceIds = sourceJson == null ? List.of() : json.readValue(sourceJson, new TypeReference<>() {});
        } catch (IOException error) {
            throw new SQLException("Invalid stored knowledge sources", error);
        }
        return new LoggedAnswer(row.getLong("id"), row.getString("question"), row.getString("answer"),
                row.getObject("field_id", Long.class), sourceIds, row.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode readMetadata(String value) throws SQLException {
        try {
            return value == null ? json.createObjectNode() : json.readTree(value);
        } catch (IOException error) {
            throw new SQLException("Invalid stored knowledge metadata", error);
        }
    }
}
