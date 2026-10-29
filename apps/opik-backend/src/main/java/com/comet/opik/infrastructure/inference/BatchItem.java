package com.comet.opik.infrastructure.inference;

/** One item of a batch submit. {@code item_id} is unique within its batch, 1 to 128 characters. */
public sealed interface BatchItem permits BatchDecideItem, BatchGenerateItem {

    int MAX_ITEM_ID_LENGTH = 128;

    String itemId();
}
