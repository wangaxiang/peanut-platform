package com.peanut.module.knowledge;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;

import com.peanut.common.ApiResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports/growth")
class GrowthReportController {
    private final GrowthReportService reports;
    private final ZoneId zone;

    GrowthReportController(GrowthReportService reports, @Value("${growth-report.zone}") String zone) {
        this.reports = reports;
        this.zone = ZoneId.of(zone);
    }

    @GetMapping("/{fieldId}")
    ApiResult<GrowthReportService.Report> generate(@PathVariable long fieldId) throws IOException {
        return ApiResult.ok(reports.generate(fieldId, LocalDate.now(zone)));
    }

    @GetMapping(value = "/{fieldId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@PathVariable long fieldId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"peanut-growth-" + fieldId + "-" + date + ".pdf\"")
                .body(reports.download(fieldId, date));
    }
}
