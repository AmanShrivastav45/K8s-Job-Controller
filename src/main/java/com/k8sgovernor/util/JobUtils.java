package com.k8sgovernor.util;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class JobUtils {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private JobUtils() {}

    /**
     * Generates a unique job name suffix: yyyyMMddHHmmss-XXXXXXXX
     */
    public static String generateJobNameSuffix() {
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(TS_FORMATTER);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return timestamp + "-" + random;
    }
}
