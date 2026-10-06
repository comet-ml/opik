package com.comet.opik.domain;

import lombok.experimental.UtilityClass;

/** Exposes package-private ThreadDAO query templates to tests in other packages. */
@UtilityClass
public class ThreadDAOTestQueries {

    public static String selectTraceThreadById() {
        return ThreadDAOImpl.SELECT_TRACES_THREAD_BY_ID;
    }
}
