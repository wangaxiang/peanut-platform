package com.peanut.module.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FieldServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
    private final FieldService fields = new FieldService(mock(FieldRepository.class), CLOCK);

    @ParameterizedTest
    @CsvSource({
        "-1, 未播种", "0, 苗期", "24, 苗期", "25, 花针期", "54, 花针期",
        "55, 结荚期", "84, 结荚期", "85, 饱果期", "119, 饱果期", "120, 收获期"
    })
    void usesSpecifiedGrowthStageBoundaries(long days, String stage) {
        LocalDate sowDate = LocalDate.now(CLOCK).minusDays(days);
        assertThat(fields.calcDaysAfterSow(sowDate)).isEqualTo(days);
        assertThat(fields.calcGrowthStage(sowDate)).isEqualTo(stage);
    }

    @Test
    void treatsMissingSowingDateAsUnsown() {
        assertThat(fields.calcDaysAfterSow(null)).isNull();
        assertThat(fields.calcGrowthStage(null)).isEqualTo("未播种");
    }

    @Test
    void recomputesGrowthStageWhenTheDateChanges() {
        LocalDate sowDate = LocalDate.now(CLOCK).minusDays(24);
        FieldService tomorrow = new FieldService(mock(FieldRepository.class), Clock.offset(CLOCK, Duration.ofDays(1)));

        assertThat(fields.calcGrowthStage(sowDate)).isEqualTo("苗期");
        assertThat(tomorrow.calcGrowthStage(sowDate)).isEqualTo("花针期");
    }
}
