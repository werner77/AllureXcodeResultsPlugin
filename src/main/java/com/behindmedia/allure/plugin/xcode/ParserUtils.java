package com.behindmedia.allure.plugin.xcode;

import io.qameta.allure.entity.Status;
import io.qameta.allure.entity.Time;
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;

import java.math.BigDecimal;
import java.util.Objects;

import static java.util.Objects.nonNull;

class ParserUtils {
    private static final BigDecimal MULTIPLICAND = new BigDecimal(1000);

    static Long getTimeInMilliseconds(final Double timeInSeconds) {
        return timeInSeconds == null ? null : BigDecimal.valueOf(timeInSeconds)
                .multiply(MULTIPLICAND)
                .longValue();
    }

    static Time getTime(final Double start, final Double duration) {
        if (duration != null) {
            try {
                final long startMs = getTimeInMilliseconds(start);
                final long durationMs = getTimeInMilliseconds(duration);

                return nonNull(startMs)
                        ? Time.create(startMs, startMs + durationMs)
                        : Time.create(durationMs);
            } catch (Exception e) {
                //Ignore
            }
        }
        return new Time();
    }

    static Status getStatus(final String testStatus) {
        if (Objects.isNull(testStatus)) {
            return Status.UNKNOWN;
        }
        if ("Success".equals(testStatus)) {
            return Status.PASSED;
        }
        if ("Failure".equals(testStatus)) {
            return Status.FAILED;
        }
        return Status.UNKNOWN;
    }

    static boolean isFlaky(final XMLPropertyListConfiguration testConfig) {
        return false;
    }

}
