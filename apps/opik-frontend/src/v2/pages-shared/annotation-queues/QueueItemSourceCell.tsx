import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Bot, User } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import QueuePill from "@/v2/pages-shared/annotation-queues/QueuePill";
import { ANNOTATION_QUEUE_ITEM_SOURCE } from "@/types/annotation-queues";
import { Trace, Thread } from "@/types/traces";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { QueueItemSourceById } from "@/v2/pages-shared/annotation-queues/useQueueItemSources";

type CustomMeta = {
  sourceById?: QueueItemSourceById;
};

/**
 * How this item got into the queue: added by a person, or matched by automation.
 *
 * The value comes from a separate membership lookup rather than the row itself - a trace knows nothing
 * about annotation queues, and its own `source` field already means something else entirely (which SDK
 * or surface produced it). Renders nothing while the lookup is in flight or if the id is absent, so a
 * slow or failed lookup leaves the column blank instead of asserting "Manual".
 */
const QueueItemSourceCell = <TData extends Trace | Thread>(
  context: CellContext<TData, unknown>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { sourceById } = (custom ?? {}) as CustomMeta;
  const source = sourceById?.[getAnnotationQueueItemId(context.row.original)];
  const automated = source === ANNOTATION_QUEUE_ITEM_SOURCE.AUTOMATED;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {source && (
        <QueuePill icon={automated ? Bot : User}>
          {automated ? "Automated" : "Manual"}
        </QueuePill>
      )}
    </CellWrapper>
  );
};

export default QueueItemSourceCell;
