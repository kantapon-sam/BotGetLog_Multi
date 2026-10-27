package com.java.botgetlog.truecorp;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** A scheduler cycle is pinned across all batches, retries and midnight. */
public final class CollectionCycle {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String CURRENT = resolve(Clock.systemUTC());

    private CollectionCycle() { }

    public static String currentDay() { return CURRENT; }

    static String resolve(Clock clock) {
        String value = System.getProperty("true.collection.day");
        if (value == null) value = System.getenv("TRUE_COLLECTION_DAY");
        return resolve(clock, value);
    }

    static String resolve(Clock clock, String assignedDay) {
        if (assignedDay != null) {
            if (!assignedDay.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !LocalDate.parse(assignedDay).toString().equals(assignedDay))
                throw new IllegalArgumentException("Invalid TRUE_COLLECTION_DAY");
            return assignedDay;
        }
        return ZonedDateTime.now(clock.withZone(BANGKOK)).plusHours(4).toLocalDate().toString();
    }
}
