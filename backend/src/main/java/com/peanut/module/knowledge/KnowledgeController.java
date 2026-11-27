package com.peanut.module.knowledge;

import static com.peanut.module.knowledge.KnowledgeModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.fasterxml.jackson.databind.JsonNode;
import com.peanut.common.ApiResult;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController {
    private final KnowledgeService knowledge;
    private final KnowledgeFileImportService fileImport;

    private final String adminToken;

    KnowledgeController(KnowledgeService knowledge, KnowledgeFileImportService fileImport,
            @Value("${knowledge.admin-token:}") String adminToken) {
        this.adminToken = adminToken;
        this.knowledge = knowledge;
        this.fileImport = fileImport;
    }

    @GetMapping("/chunks")
    ApiResult<Page<Chunk>> chunks(Pageable page, @RequestHeader(name = "X-Knowledge-Admin-Token", defaultValue = "") String token) {
        requireAdmin(token);
        return ApiResult.ok(knowledge.chunks(page));
    }

    @PostMapping("/chunks")
    ApiResult<Object> chunks(@RequestBody JsonNode body,
                            @RequestHeader(name = "X-Knowledge-Admin-Token", defaultValue = "") String token) {
        requireAdmin(token);
        return ApiResult.ok(knowledge.insert(body));
    }

    @PostMapping(value = "/chunks/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResult<ImportResult> importCsv(@RequestParam MultipartFile file,
                                      @RequestHeader(name = "X-Knowledge-Admin-Token", defaultValue = "") String token) {
        requireAdmin(token);
        return ApiResult.ok(knowledge.csv(file));
    }

    @PostMapping(value = "/chunks/import/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResult<KnowledgeFileImportService.ImportSummary> importFile(@RequestParam MultipartFile file,
            @RequestHeader(name = "X-Knowledge-Admin-Token", defaultValue = "") String token) {
        requireAdmin(token);
        return ApiResult.ok(fileImport.importFile(file));
    }

    private void requireAdmin(String token) {
        if (!adminToken.isBlank() && !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请输入知识库管理密钥");
        }
    }

    @PostMapping("/qa")
    ApiResult<Answer> answer(@Valid @RequestBody Question question) {
        return ApiResult.ok(knowledge.answer(question));
    }

    @GetMapping("/qa/history")
    ApiResult<Page<Answer>> history(@RequestParam(required = false) Long fieldId, Pageable page) {
        return ApiResult.ok(knowledge.history(fieldId, page));
    }
}
