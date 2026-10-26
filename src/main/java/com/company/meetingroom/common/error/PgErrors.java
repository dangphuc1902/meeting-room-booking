package com.company.meetingroom.common.error;

import java.sql.SQLException;

/** Helpers to recognise PostgreSQL constraint violations behind Spring's exception wrappers. */
public final class PgErrors {

    /** SQLSTATE raised when an EXCLUDE constraint (our no-overlap rule) is violated. */
    public static final String EXCLUSION_VIOLATION = "23P01";
    public static final String UNIQUE_VIOLATION = "23505";

    private PgErrors() {
    }

    public static boolean isExclusionViolation(Throwable ex) {
        return hasSqlState(ex, EXCLUSION_VIOLATION);
    }

    public static boolean isUniqueViolation(Throwable ex) {
        return hasSqlState(ex, UNIQUE_VIOLATION);
    }

    private static boolean hasSqlState(Throwable ex, String state) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
