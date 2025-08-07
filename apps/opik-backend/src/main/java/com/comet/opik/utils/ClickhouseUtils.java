package com.comet.opik.utils;

import com.comet.opik.infrastructure.AsyncInsertConfig;
import org.stringtemplate.v4.ST;

public class ClickhouseUtils {

    public static String ASYNC_INSERT = "SETTINGS async_insert=1, wait_for_async_insert=1, async_insert_use_adaptive_busy_timeout=1,async_insert_deduplicate=1";

    public static void checkAsyncConfig(ST template, AsyncInsertConfig asyncInsert) {
        if (asyncInsert.enabled()) {
            template.add("settings_clause", ClickhouseUtils.ASYNC_INSERT);
        }
    }
}
