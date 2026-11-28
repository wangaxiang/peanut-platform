package com.peanut.module.patrol;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PatrolCronTest {
    @Test
    void cronMatchesEitherRestrictedDayField() {
        PatrolCron cron = PatrolCron.parse("0 6 3 * MON");
        assertThat(cron.matches(at("2026-11-03T06:00"))).isTrue();
        assertThat(cron.matches(at("2026-11-09T06:00"))).isTrue();
        assertThat(cron.matches(at("2026-11-04T06:00"))).isFalse();
        assertThat(cron.matches(at("2026-11-09T06:01"))).isFalse();
    }

    @Test
    void cronValidatesFiveFieldsAndSupportsWeekdayLists() {
        PatrolCron cron = PatrolCron.parse("0 6 * * 1,4");
        assertThat(cron.matches(at("2026-11-02T06:00"))).isTrue();
        assertThat(cron.matches(at("2026-11-05T06:00"))).isTrue();
        assertThat(cron.matches(at("2026-11-06T06:00"))).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PatrolCron.parse("0 0 6 * * 1"));
        assertThatIllegalArgumentException().isThrownBy(() -> PatrolCron.parse("0 6 L * *"));
    }

    private ZonedDateTime at(String time) {
        return ZonedDateTime.parse(time + ":00+08:00[Asia/Shanghai]");
    }
}
