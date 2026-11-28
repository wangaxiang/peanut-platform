package com.peanut.module.patrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatrolSensorReportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsReadingsWithoutFieldOrWithTheRegisteredField() throws Exception {
        assertThat(valid("{\"metrics\":{\"soil_temp\":24.5}}")).isTrue();
        assertThat(valid("{\"field_id\":4,\"soil_n\":85}")).isTrue();
    }

    @Test
    void rejectsForgedBindingsIncludingConflictingAliases() throws Exception {
        assertThat(valid("{\"fieldId\":18,\"metrics\":{\"soil_temp\":24.5}}")).isFalse();
        assertThat(valid("{\"fieldId\":4,\"field_id\":18,\"soil_n\":85}")).isFalse();
    }

    @Test
    void rejectsMissingMetricsAndInvalidTimestamps() throws Exception {
        assertThat(valid("{\"metrics\":{\"air_temp\":18}}")).isFalse();
        assertThat(valid("{\"timestamp\":-1,\"soil_temp\":24}")).isFalse();
        assertThat(valid("{\"soil_temp\":\"24\"}")).isFalse();
    }

    private boolean valid(String report) throws Exception {
        return PatrolDeviceService.validSensorReport(mapper.readTree(report),4);
    }
}
