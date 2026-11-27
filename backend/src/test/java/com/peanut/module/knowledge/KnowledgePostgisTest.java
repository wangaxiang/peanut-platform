package com.peanut.module.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "PEANUT_TEST_POSTGIS", matches = "true")
@SpringBootTest(properties = "knowledge.admin-token=knowledge-test-token")
@AutoConfigureMockMvc
@Transactional
class KnowledgePostgisTest {
    private static final String ADMIN = "X-Knowledge-Admin-Token";
    private static final String TOKEN = "knowledge-test-token";

    @Autowired
    private MockMvc http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private GrowthReportJob reports;
    @Autowired
    private GrowthReportService reportService;
    @MockBean
    private KnowledgeModelClient model;

    @Test
    void recordsQuestionAndResolvesTitlesForTheSelectedField() throws Exception {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long fieldId = addField(date.minusDays(35));
        long chunkId = exampleChunk("花针期管理手册");
        when(model.answer(any())).thenReturn(new KnowledgeModels.ModelAnswer("关注土壤水分。", List.of(chunkId)));

        http.perform(post("/api/knowledge/qa").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("question", "花针期需要注意什么？")
                                .put("fieldId", fieldId).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].sourceTitle").value("花针期管理手册"));
        http.perform(get("/api/knowledge/qa/history").param("fieldId", Long.toString(fieldId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].fieldId").value(fieldId))
                .andExpect(jsonPath("$.data.content[0].answer").value("关注土壤水分。"));
        assertThat(jdbc.queryForObject("SELECT field_id FROM qa_log WHERE field_id = ?", Long.class, fieldId))
                .isEqualTo(fieldId);
    }

    @Test
    void validatesAdminAccessAndReportsCsvFailuresPerRow() throws Exception {
        when(model.embed(any())).thenAnswer(call -> {
            KnowledgeModels.ChunkRequest chunk = call.getArgument(0);
            return exampleChunk(chunk.sourceTitle());
        });
        byte[] bytes = """
                sourceTitle,content,category,variety,growthStage
                花针手册,"田间水分,需观察",栽培,花育25号,花针期
                空资料,,栽培,,
                播种手册,播前准备,栽培,,播种
                """.getBytes(StandardCharsets.UTF_8);
        MockMultipartFile csv = new MockMultipartFile("file", "knowledge.csv", "text/csv", bytes);
        http.perform(multipart("/api/knowledge/chunks/import").file(csv))
                .andExpect(status().isUnauthorized());
        http.perform(multipart("/api/knowledge/chunks/import").file(csv).header(ADMIN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failureCount").value(1))
                .andExpect(jsonPath("$.data.failures[0].row").value(3));
        http.perform(get("/api/knowledge/chunks").header(ADMIN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].sourceTitle").value("播种手册"));
    }

    @Test
    void scheduledReportUsesExistingDataAndProducesPdf() throws Exception {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long fieldId = addField(date.minusDays(35));
        jdbc.update("""
                INSERT INTO farm_event (field_id, event_type, event_date, amount, unit, remark)
                VALUES (?, 'fertilize', ?, 12.5, 'kg', '样例施肥')
                """, fieldId, date.minusDays(1));

        reports.generate(date);
        String url = jdbc.queryForObject("SELECT pdf_url FROM growth_report WHERE field_id = ?",
                String.class, fieldId);
        assertThat(url).contains("/api/reports/growth/" + fieldId + "/pdf");
        byte[] pdf = http.perform(get("/api/reports/growth/{id}/pdf", fieldId).param("date", date.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void uploadsPdfAndDeliversASignedLinkWithRetryAndDeduplication() throws Exception {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long fieldId = addField(date.minusDays(35));
        var report = reportService.generate(fieldId, date);
        byte[] pdf = reportService.download(fieldId, date);
        String key = "reports/" + fieldId + "/" + date + ".pdf";
        var upload = new AtomicReference<byte[]>();
        var signature = new AtomicReference<String>();
        var notification = new AtomicReference<byte[]>();
        var idempotency = new AtomicReference<String>();
        var deliveries = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", request -> {
            try (request) {
                notification.set(request.getRequestBody().readAllBytes());
                idempotency.set(request.getRequestHeaders().getFirst("Idempotency-Key"));
                request.sendResponseHeaders(deliveries.incrementAndGet() == 1 ? 503 : 204, -1);
            }
        });
        server.createContext("/", request -> {
            try (request) {
                if (request.getRequestMethod().equals("PUT")) {
                    upload.set(request.getRequestBody().readAllBytes());
                    signature.set(request.getRequestHeaders().getFirst("Authorization"));
                    request.getResponseHeaders().add("ETag", "\"" + java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("MD5").digest(upload.get())) + "\"");
                    request.sendResponseHeaders(200, -1);
                } else {
                    byte[] object = upload.get();
                    request.getResponseHeaders().add("Content-Type", "application/pdf");
                    request.sendResponseHeaders(200, object.length);
                    request.getResponseBody().write(object);
                }
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new java.io.IOException(error);
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var storage = new GrowthReportStorage("unused", endpoint, "cn-hangzhou", "report-test",
                "local-test-key", "local-test-secret");
        try {
            storage.save(key, pdf);
            assertThat(upload.get()).isEqualTo(pdf);
            assertThat(signature.get()).startsWith("OSS4-HMAC-SHA256");
            var notifier = new GrowthReportNotifier(new NamedParameterJdbcTemplate(jdbc), storage,
                    endpoint + "/notify", "", "");
            assertThatThrownBy(() -> notifier.deliver(report)).hasMessage("Report notification returned HTTP 503");
            notifier.deliver(report);
            notifier.deliver(report);
            assertThat(deliveries.get()).isEqualTo(2);
            assertThat(idempotency.get()).isEqualTo("growth-report-" + fieldId + "-" + date);
            var message = json.readTree(notification.get());
            assertThat(message.path("fieldId").asLong()).isEqualTo(fieldId);
            assertThat(message.path("reportDate").asText()).isEqualTo(date.toString());
            var url = URI.create(message.path("pdfUrl").asText());
            assertThat(url.getPath()).endsWith("/" + key);
            assertThat(url.getQuery()).contains("x-oss-signature-version=OSS4-HMAC-SHA256", "x-oss-expires=");
            var download = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(pdf);
        } finally {
            storage.close();
            server.stop(0);
        }
    }

    @Test
    void importsPdfAndZipDocumentsAndReportsUnreadableEntries() throws Exception {
        when(model.embed(any())).thenAnswer(call -> {
            KnowledgeModels.ChunkRequest chunk = call.getArgument(0);
            return jdbc.queryForObject("""
                    INSERT INTO knowledge_chunk (source_title, content, metadata)
                    VALUES (?, ?, CAST(? AS jsonb)) RETURNING id
                    """, Long.class, chunk.sourceTitle(), chunk.content(), json.writeValueAsString(chunk.metadata()));
        });

        MockMultipartFile pdf = new MockMultipartFile("file", "field-guide.pdf", "application/pdf", pdfWithText());
        http.perform(multipart("/api/knowledge/chunks/import/file").file(pdf).header(ADMIN, TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1));
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunk WHERE source_title = 'field-guide.pdf'",
                String.class)).contains("Peanut growth field guide");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("农技/田间管理.txt", "花针期关注土壤水分。".getBytes(StandardCharsets.UTF_8));
        entries.put("农技/作业计划.docx", wordWithText());
        entries.put("农技/资料.csv", """
                sourceTitle,content,category,variety,growthStage
                采收资料,查看田间成熟情况,栽培,,收获期
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("农技/不可读.rar", new byte[]{1, 2, 3});
        MockMultipartFile archive = new MockMultipartFile("file", "资料.zip", "application/zip", zip(entries));

        http.perform(multipart("/api/knowledge/chunks/import/file").file(archive))
                .andExpect(status().isUnauthorized());
        http.perform(multipart("/api/knowledge/chunks/import/file").file(archive).header(ADMIN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(3))
                .andExpect(jsonPath("$.data.failedFiles").value(1))
                .andExpect(jsonPath("$.data.files[3].name").value("资料.zip / 农技/不可读.rar"));
        assertThat(jdbc.queryForObject("""
                SELECT metadata->>'archiveEntry' FROM knowledge_chunk WHERE source_title = '作业计划.docx'
                """, String.class)).isEqualTo("农技/作业计划.docx");
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunk WHERE source_title = '作业计划.docx'",
                String.class)).isEqualTo("花生苗期记录");
    }

    @Test
    void explainsThatImageOnlyPdfNeedsOcr() throws Exception {
        byte[] emptyPdf;
        try (var pdf = new PDDocument(); var buffer = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(buffer);
            emptyPdf = buffer.toByteArray();
        }
        var file = new MockMultipartFile("file", "扫描件.pdf", "application/pdf", emptyPdf);
        http.perform(multipart("/api/knowledge/chunks/import/file").file(file).header(ADMIN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failedFiles").value(1))
                .andExpect(jsonPath("$.data.files[0].message", org.hamcrest.Matchers.containsString("OCR")));
    }

    private static byte[] pdfWithText() throws Exception {
        try (var pdf = new PDDocument(); var buffer = new ByteArrayOutputStream()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var text = new PDPageContentStream(pdf, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(30, 750);
                text.showText("Peanut growth field guide");
                text.endText();
            }
            pdf.save(buffer);
            return buffer.toByteArray();
        }
    }

    private static byte[] wordWithText() throws Exception {
        return zip(Map.of("word/document.xml", """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>花生苗期记录</w:t></w:r></w:p></w:body>
                </w:document>
                """.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }


    private long exampleChunk(String title) {
        return jdbc.queryForObject("""
                INSERT INTO knowledge_chunk (source_title, content, embedding)
                VALUES (?, '花生管理资料', array_fill(1::real, ARRAY[1536])::vector)
                RETURNING id
                """, Long.class, title);
    }

    private long addField(LocalDate sowDate) {
        return jdbc.queryForObject("""
                INSERT INTO field (name, geometry, area_mu, variety, sow_date)
                VALUES ('东区报告验收田', ST_MakeEnvelope(115.60, 36.13, 115.61, 36.14, 4326), 12.34, '花育25号', ?)
                RETURNING id
                """, Long.class, sowDate);
    }
}
