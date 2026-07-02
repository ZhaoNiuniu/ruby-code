package io.github.mengru.agent.core.scheduler;

public enum ScheduledJobType {
    CRON("cron"),
    ONCE("once");

    private final String value;

    ScheduledJobType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ScheduledJobType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("type must be cron or once");
        }
        for (ScheduledJobType type : values()) {
            if (type.value.equals(value.strip())) {
                return type;
            }
        }
        throw new IllegalArgumentException("type must be cron or once");
    }
}
