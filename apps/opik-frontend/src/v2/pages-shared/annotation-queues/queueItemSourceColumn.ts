import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { Thread, Trace } from "@/types/traces";
import QueueItemSourceCell from "@/v2/pages-shared/annotation-queues/QueueItemSourceCell";
import { QueueItemSourceById } from "@/v2/pages-shared/annotation-queues/useQueueItemSources";

export const QUEUE_ITEM_SOURCE_COLUMN_ID = "queue_item_source";

/**
 * The Source column for an annotation queue's items table.
 *
 * Kept out of the tables' own column lists on purpose: queue membership is not a trace or thread
 * field, so those APIs can neither filter nor sort on it, and those lists are what the filter columns
 * are built from. A column offered there would produce a filter no query could honour.
 */
export const createQueueItemSourceColumn = <
  T extends Trace | Thread,
>(): ColumnData<T> => ({
  id: QUEUE_ITEM_SOURCE_COLUMN_ID,
  label: "Source",
  type: COLUMN_TYPE.string,
  cell: QueueItemSourceCell as never,
});

/**
 * Hands the membership lookup to the Source column, leaving every other column untouched. Both items
 * tables inject it the same way, so the column list stays the single description of what is shown.
 */
export const withQueueItemSources = <T extends Trace | Thread>(
  columns: ColumnData<T>[],
  sourceById: QueueItemSourceById,
): ColumnData<T>[] =>
  columns.map((column) =>
    column.id === QUEUE_ITEM_SOURCE_COLUMN_ID
      ? { ...column, customMeta: { sourceById } }
      : column,
  );
