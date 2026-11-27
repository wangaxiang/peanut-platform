package com.peanut.module.knowledge;

import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class GrowthReportJob {
    private static final Logger LOG = LoggerFactory.getLogger(GrowthReportJob.class);
    private final GrowthReportService reports;
    private final GrowthReportNotifier notifier;
    private final ZoneId zone;

    GrowthReportJob(GrowthReportService reports, GrowthReportNotifier notifier,
                    @Value("${growth-report.zone}") String zone) {
        this.reports = reports;
        this.notifier = notifier;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${growth-report.cron}", zone = "${growth-report.zone}")
    public void generateWeeklyReports() {
        generate(LocalDate.now(zone));
    }

    void generate(LocalDate date) {
        for (long fieldId : reports.activeFields(date)) {
            try {
                notifier.deliver(reports.generate(fieldId, date));
            } catch (Exception error) {
                LOG.error("Weekly growth report failed for field {}", fieldId, error);
            }
        }
    }
}
