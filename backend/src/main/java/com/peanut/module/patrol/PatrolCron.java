package com.peanut.module.patrol;

import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;

record PatrolCron(CronExpression first, CronExpression second) {
    static PatrolCron parse(String value) {
        String[] fields = value.strip().split("\\s+");
        if (fields.length != 5 || !fields[0].matches("[0-9*,/\\-]+")
                || !fields[1].matches("[0-9*,/\\-]+") || !fields[2].matches("[0-9*,/\\-]+")
                || !fields[3].matches("(?i)(?:[0-9*,/\\-]|JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)+")
                || !fields[4].matches("(?i)(?:[0-9*,/\\-]|SUN|MON|TUE|WED|THU|FRI|SAT)+")) {
            throw new IllegalArgumentException("请输入 5 段 cron 表达式");
        }
        if (fields[2].startsWith("*") || fields[4].startsWith("*")) {
            return new PatrolCron(CronExpression.parse("0 " + String.join(" ", fields)), null);
        }
        String prefix = "0 " + fields[0] + " " + fields[1] + " ";
        return new PatrolCron(CronExpression.parse(prefix + fields[2] + " " + fields[3] + " *"),
                CronExpression.parse(prefix + "* " + fields[3] + " " + fields[4]));
    }

    boolean matches(ZonedDateTime minute) {
        ZonedDateTime before = minute.minusSeconds(1);
        return minute.equals(first.next(before)) || (second != null && minute.equals(second.next(before)));
    }
}
