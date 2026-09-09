import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Bot, User } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { ANNOTATION_QUEUE_ITEM_SOURCE } from "@/types/annotation-queues";
import { Trace, Thread } from "@/types/traces";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";

type CustomMeta = {
  /** item id -> how it got into the queue, from the membership lookup. */
  sourceById?: Record<string, ANNOTATION_QUEUE_ITEM_SOURCE>;
};

/**
 * How this item got into the queue: added by a person, or matched by automation.
 *
 * <p>The value comes from a separate membership lookup rather than the row itself — a trace knows
 * nothing about annotation queues, and its own {@code source} field already means something else
 * entirely (which SDK or surface produced it). Renders nothing while the lookup is in flight or if the
 * id is absent, so a slow or failed lookup leaves the column blank instead of asserting "Manual".
 *
 * <p>Serves both items tables. The id is resolved through {@code getAnnotationQueueItemId} because a
 * thread is a queue item under its {@code thread_model_id}, not the {@code id} the table shows.
 */
const QueueItemSourceCell = <TData extends Trace | Thread>(
  context: CellContext<TData, unknown>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { sourceById } = (custom ?? {}) as CustomMeta;
  const source = sourceById?.[getAnnotationQueueItemId(context.row.original)];
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {source && (
        <Tag size={tagSize} variant="gray" className="flex items-center gap-1">
          {source === ANNOTATION_QUEUE_ITEM_SOURCE.AUTOMATED ? (
            <Bot className="size-3 shrink-0 text-muted-gray" />
          ) : (
            <User className="size-3 shrink-0 text-muted-gray" />
          )}
          {source === ANNOTATION_QUEUE_ITEM_SOURCE.AUTOMATED
            ? "Automated"
            : "Manual"}
        </Tag>
      )}
    </CellWrapper>
  );
};

export default QueueItemSourceCell;
