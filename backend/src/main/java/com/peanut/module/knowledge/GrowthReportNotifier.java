package com.peanut.module.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;

@Component
class GrowthReportNotifier {
    private static final URI PUSHPLUS_SEND = URI.create("https://www.pushplus.plus/send");
    private final NamedParameterJdbcTemplate jdbc;
    private final RestClient client;
    private final GrowthReportStorage storage;
    private final URI webhook;
    private final URI publicBase;
    private final String pushPlusToken;

    GrowthReportNotifier(NamedParameterJdbcTemplate jdbc, GrowthReportStorage storage,
            @Value("${growth-report.webhook:}") String webhook,
            @Value("${growth-report.public-base-url:}") String publicBase,
            @Value("${growth-report.pushplus-token:}") String pushPlusToken) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.webhook = webhook.isBlank() ? null : URI.create(webhook);
        this.publicBase = publicBase.isBlank() ? null : URI.create(publicBase);
        this.pushPlusToken = pushPlusToken.strip();
        if (this.webhook != null && !this.pushPlusToken.isEmpty()) {
            throw new IllegalArgumentException("Configure one growth report notification channel");
        }
        if ((this.webhook != null || !this.pushPlusToken.isEmpty()) && this.publicBase == null && !storage.hasCloudStorage()) {
            throw new IllegalArgumentException("Report notifications need OSS or a public report base URL");
        }
        if (this.publicBase != null && !this.publicBase.isAbsolute()) {
            throw new IllegalArgumentException("The public report base URL must be absolute");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Transactional
    public void deliver(GrowthReportService.Report report) {
        if (webhook == null && pushPlusToken.isEmpty()) {
            return;
        }
        Map<String, Object> key = Map.of("fieldId", report.fieldId(), "date", report.reportDate());
        var pending = jdbc.queryForList("""
                SELECT storage_key FROM growth_report WHERE field_id = :fieldId AND report_date = :date
                  AND notification_sent_at IS NULL FOR UPDATE
                """, key, String.class);
        if (pending.isEmpty()) {
            return;
        }
        String pdfUrl = publicBase == null ? storage.signedDownloadUrl(pending.getFirst())
                : publicBase.resolve(report.pdfUrl()).toString();
        try {
            if (webhook != null) {
                client.post().uri(webhook)
                        .header("Idempotency-Key", "growth-report-" + report.fieldId() + "-" + report.reportDate())
                        .body(Map.of("fieldId", report.fieldId(), "reportDate", report.reportDate().toString(), "pdfUrl", pdfUrl))
                        .retrieve().toBodilessEntity();
            } else {
                String title = "花生生长周报 · " + report.reportDate();
                String content = "<p>地块编号：" + report.fieldId() + "</p><p><a href=\""
                        + HtmlUtils.htmlEscape(pdfUrl) + "\">打开 PDF 周报</a></p>";
                JsonNode receipt = client.post().uri(PUSHPLUS_SEND).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("token", pushPlusToken, "title", title, "content", content,
                                "channel", "wechat", "template", "html"))
                        .retrieve().body(JsonNode.class);
                int code = receipt == null ? -1 : receipt.path("code").asInt(-1);
                if (code != 200) {
                    throw new IllegalStateException("PushPlus rejected the report notification (code " + code + ")");
                }
            }
        } catch (RestClientResponseException error) {
            throw new IllegalStateException("Report notification returned HTTP " + error.getStatusCode().value());
        } catch (RestClientException error) {
            throw new IllegalStateException("Could not reach the report notification service");
        }
        jdbc.update("""
                UPDATE growth_report SET notification_sent_at = NOW()
                WHERE field_id = :fieldId AND report_date = :date
                """, key);
    }
}
