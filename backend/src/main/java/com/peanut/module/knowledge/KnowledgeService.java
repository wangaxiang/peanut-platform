package com.peanut.module.knowledge;

import static com.peanut.module.knowledge.KnowledgeModels.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class KnowledgeService {
    private static final List<String> CSV_COLUMNS = List.of("sourceTitle", "content", "category", "variety", "growthStage");
    private static final int MAX_BATCH_SIZE = 100;
    private final KnowledgeRepository records;
    private final KnowledgeModelClient model;
    private final ObjectMapper json;
    private final Validator validator;

    KnowledgeService(KnowledgeRepository records, KnowledgeModelClient model, ObjectMapper json, Validator validator) {
        this.records = records;
        this.model = model;
        this.json = json;
        this.validator = validator;
    }

    Page<Chunk> chunks(Pageable page) {
        return records.chunks(newest(page));
    }

    Object insert(JsonNode body) {
        if (!body.isArray()) {
            return Map.of("chunkId", model.embed(readChunk(body)));
        }
        if (body.isEmpty() || body.size() > MAX_BATCH_SIZE) {
            throw badRequest("批量录入需要 1 至 100 条知识片段");
        }
        List<Long> ids = new ArrayList<>();
        List<ImportFailure> failures = new ArrayList<>();
        int row = 0;
        for (JsonNode item : body) {
            row++;
            try {
                ids.add(model.embed(readChunk(item)));
            } catch (ResponseStatusException error) {
                failures.add(new ImportFailure(row, error.getReason()));
            }
        }
        return new ImportResult(ids.size(), failures.size(), ids, failures);
    }

    ImportResult csv(MultipartFile file) {
        try {
            return csv(file.getInputStream(), MAX_BATCH_SIZE);
        } catch (IOException error) {
            throw badRequest("CSV 文件无法读取");
        }
    }

    long importChunk(ChunkRequest request) {
        validate(request);
        return model.embed(request);
    }

    ImportResult csv(InputStream input, int limit) {
        List<NumberedChunk> chunks = readCsv(input, limit);
        List<Long> ids = new ArrayList<>();
        List<ImportFailure> failures = new ArrayList<>();
        for (NumberedChunk chunk : chunks) {
            try {
                ids.add(importChunk(chunk.request()));
            } catch (ResponseStatusException error) {
                failures.add(new ImportFailure(chunk.number(), error.getReason()));
            }
        }
        return new ImportResult(ids.size(), failures.size(), ids, failures);
    }

    Answer answer(Question request) {
        if (request.fieldId() != null && !records.fieldExists(request.fieldId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "所选地块不存在");
        }
        Question question = new Question(request.question().strip(), request.fieldId(), request.history());
        ModelAnswer reply = model.answer(question);
        Set<Long> distinctIds = new java.util.LinkedHashSet<>(reply.sources());
        Map<Long, String> titles = records.titles(distinctIds);
        List<Long> sources = distinctIds.stream().filter(titles::containsKey).toList();
        return records.saveAnswer(question, reply.answer(), sources).withSources(titles);
    }

    Page<Answer> history(Long fieldId, Pageable page) {
        if (fieldId != null && fieldId <= 0) {
            throw badRequest("地块编号必须为正数");
        }
        Page<LoggedAnswer> result = records.history(fieldId, newest(page));
        Set<Long> ids = new HashSet<>();
        result.forEach(entry -> ids.addAll(entry.sourceIds()));
        Map<Long, String> titles = records.titles(ids);
        return result.map(entry -> entry.withSources(titles));
    }

    private List<NumberedChunk> readCsv(InputStream input, int limit) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            reader.mark(1);
            if (reader.read() != '\ufeff') {
                reader.reset();
            }
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
            try (CSVParser parser = format.parse(reader)) {
                if (!CSV_COLUMNS.equals(parser.getHeaderNames())) {
                    throw badRequest("CSV 表头应为 sourceTitle,content,category,variety,growthStage");
                }
                List<NumberedChunk> rows = new ArrayList<>();
                for (CSVRecord row : parser) {
                    if (rows.size() == limit) {
                        throw badRequest("本次剩余可导入 " + limit + " 条知识片段，请拆分 CSV");
                    }
                    ObjectNode metadata = json.createObjectNode();
                    for (String name : CSV_COLUMNS.subList(2, CSV_COLUMNS.size())) {
                        if (row.isSet(name) && !row.get(name).isBlank()) {
                            metadata.put(name, row.get(name).strip());
                        }
                    }
                    rows.add(new NumberedChunk(row.getRecordNumber() + 1,
                            new ChunkRequest(row.isSet("sourceTitle") ? row.get("sourceTitle") : null,
                                    row.isSet("content") ? row.get("content") : null, metadata)));
                }
                return rows;
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException error) {
            throw badRequest("CSV 无法解析，请检查 UTF-8 编码和引号格式");
        }
    }

    private ChunkRequest readChunk(JsonNode body) {
        if (!body.isObject()) {
            throw badRequest("每条知识片段必须是 JSON 对象");
        }
        try {
            ChunkRequest request = json.treeToValue(body, ChunkRequest.class);
            validate(request);
            return request;
        } catch (JsonProcessingException error) {
            throw badRequest("知识片段的格式有误");
        }
    }

    private void validate(ChunkRequest chunk) {
        String errors = validator.validate(chunk).stream()
                .map(error -> error.getPropertyPath() + " " + error.getMessage())
                .sorted().collect(Collectors.joining(", "));
        if (!errors.isEmpty()) {
            throw badRequest(errors);
        }
        if (chunk.metadata() != null && !chunk.metadata().isObject()) {
            throw badRequest("metadata 必须是 JSON 对象");
        }
    }

    private Pageable newest(Pageable page) {
        return PageRequest.of(page.getPageNumber(), Math.min(page.getPageSize(), 100),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record NumberedChunk(long number, ChunkRequest request) {
    }
}
