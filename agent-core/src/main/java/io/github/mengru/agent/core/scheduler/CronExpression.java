package io.github.mengru.agent.core.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

public final class CronExpression {

    private static final int SEARCH_DAYS = 400;

    private final String expression;
    private final CronField seconds;
    private final CronField minutes;
    private final CronField hours;
    private final CronField daysOfMonth;
    private final CronField months;
    private final CronField weekdays;

    private CronExpression(
            String expression,
            CronField seconds,
            CronField minutes,
            CronField hours,
            CronField daysOfMonth,
            CronField months,
            CronField weekdays
    ) {
        this.expression = expression;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.weekdays = weekdays;
    }

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cronExpression must not be blank");
        }
        String[] fields = expression.strip().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException("cronExpression must have 6 fields: second minute hour day month weekday");
        }
        return new CronExpression(
                expression.strip(),
                CronField.parse(fields[0], 0, 59, "second"),
                CronField.parse(fields[1], 0, 59, "minute"),
                CronField.parse(fields[2], 0, 23, "hour"),
                CronField.parse(fields[3], 1, 31, "day"),
                CronField.parse(fields[4], 1, 12, "month"),
                CronField.parse(fields[5], 0, 7, "weekday")
        );
    }

    public Instant nextAfter(Instant after, ZoneId zoneId) {
        Objects.requireNonNull(after, "after must not be null");
        ZoneId resolvedZone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        ZonedDateTime cursor = after.atZone(resolvedZone).plusSeconds(1).withNano(0);
        LocalDate startDate = cursor.toLocalDate();
        for (int dayOffset = 0; dayOffset <= SEARCH_DAYS; dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            if (!matchesDate(date)) {
                continue;
            }
            for (int hour : hours.orderedValues()) {
                if (dayOffset == 0 && hour < cursor.getHour()) {
                    continue;
                }
                for (int minute : minutes.orderedValues()) {
                    if (dayOffset == 0 && hour == cursor.getHour() && minute < cursor.getMinute()) {
                        continue;
                    }
                    for (int second : seconds.orderedValues()) {
                        if (dayOffset == 0
                                && hour == cursor.getHour()
                                && minute == cursor.getMinute()
                                && second < cursor.getSecond()) {
                            continue;
                        }
                        ZonedDateTime candidate = ZonedDateTime.of(date, LocalTime.of(hour, minute, second), resolvedZone);
                        if (candidate.toInstant().isAfter(after)) {
                            return candidate.toInstant();
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("cronExpression has no matching time within " + SEARCH_DAYS + " days: " + expression);
    }

    private boolean matchesDate(LocalDate date) {
        if (!months.matches(date.getMonthValue())) {
            return false;
        }
        if (!daysOfMonth.matches(date.getDayOfMonth())) {
            return false;
        }
        int cronWeekday = date.getDayOfWeek().getValue() % 7;
        return weekdays.matches(cronWeekday) || (cronWeekday == 0 && weekdays.matches(7));
    }

    @Override
    public String toString() {
        return expression;
    }

    private record CronField(BitSet values, List<Integer> orderedValues) {

        static CronField parse(String raw, int min, int max, String label) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(label + " field must not be blank");
            }
            if (raw.matches(".*[?LW#].*")) {
                throw new IllegalArgumentException(label + " field uses unsupported cron syntax: " + raw);
            }
            BitSet values = new BitSet(max + 1);
            for (String token : raw.split(",")) {
                addToken(values, token.strip(), min, max, label);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(label + " field did not select any values");
            }
            List<Integer> ordered = new ArrayList<>();
            for (int value = values.nextSetBit(min); value >= 0 && value <= max; value = values.nextSetBit(value + 1)) {
                ordered.add(value);
            }
            return new CronField(values, List.copyOf(ordered));
        }

        private static void addToken(BitSet values, String token, int min, int max, String label) {
            if (token.isBlank()) {
                throw new IllegalArgumentException(label + " field contains an empty list item");
            }
            if ("*".equals(token)) {
                values.set(min, max + 1);
                return;
            }
            if (token.startsWith("*/")) {
                int step = parseInt(token.substring(2), label, token);
                if (step < 1) {
                    throw new IllegalArgumentException(label + " step must be greater than zero: " + token);
                }
                for (int value = min; value <= max; value += step) {
                    values.set(value);
                }
                return;
            }
            int dash = token.indexOf('-');
            if (dash >= 0) {
                int start = parseInt(token.substring(0, dash), label, token);
                int end = parseInt(token.substring(dash + 1), label, token);
                validateRange(start, min, max, label, token);
                validateRange(end, min, max, label, token);
                if (end < start) {
                    throw new IllegalArgumentException(label + " range end must be >= start: " + token);
                }
                values.set(start, end + 1);
                return;
            }
            int value = parseInt(token, label, token);
            validateRange(value, min, max, label, token);
            values.set(value);
        }

        private static int parseInt(String raw, String label, String token) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " field contains invalid value: " + token, e);
            }
        }

        private static void validateRange(int value, int min, int max, String label, String token) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + " value out of range in " + token + ": expected " + min + "-" + max);
            }
        }

        boolean matches(int value) {
            return value >= 0 && value < values.length() && values.get(value);
        }
    }
}
