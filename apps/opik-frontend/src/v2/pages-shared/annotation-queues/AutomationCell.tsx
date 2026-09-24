import React from "react";
import { CellContext } from "@tanstack/react-table";
import { CircleStop, Zap, ZapOff } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import QueuePill from "@/v2/pages-shared/annotation-queues/QueuePill";
import { AnnotationQueue } from "@/types/annotation-queues";

/**
 * Whether a queue populates itself.
 *
 * "On" means matching items are added automatically; it does not mean the queue is closed to people -
 * manual additions keep working either way, which is why the label is On/Off rather than
 * Automatic/Manual. "Cap reached" is On with nothing left to add: automation has hit its ceiling and
 * will resume as soon as the cap is raised or automated items are removed.
 */
const AutomationCell: React.FC<CellContext<AnnotationQueue, unknown>> = (
  context,
) => {
  const queue = context.row.original;
  const enabled = Boolean(queue.automation?.enabled);
  const cap = queue.automation?.max_items_in_queue;
  // Approximate: the cap counts automated items only, but the payload carries the total, so a queue
  // whose manual additions push it past the cap shows as reached while automation still has room.
  const capReached = enabled && cap != null && queue.items_count >= cap;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {capReached ? (
        <QueuePill icon={CircleStop}>Cap reached</QueuePill>
      ) : (
        <QueuePill icon={enabled ? Zap : ZapOff}>
          {enabled ? "On" : "Off"}
        </QueuePill>
      )}
    </CellWrapper>
  );
};

export default AutomationCell;
