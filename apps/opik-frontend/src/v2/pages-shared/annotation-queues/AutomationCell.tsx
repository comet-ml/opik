import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Zap, ZapOff } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import QueuePill from "@/v2/pages-shared/annotation-queues/QueuePill";
import { AnnotationQueue } from "@/types/annotation-queues";

/**
 * Whether a queue populates itself.
 *
 * <p>"On" means matching items are added automatically; it does not mean the queue is closed to people —
 * manual additions keep working either way, which is why the label is On/Off rather than
 * Automatic/Manual.
 */
const AutomationCell: React.FC<CellContext<AnnotationQueue, unknown>> = (
  context,
) => {
  const queue = context.row.original;
  const enabled = Boolean(queue.automation?.enabled);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <QueuePill icon={enabled ? Zap : ZapOff}>
        {enabled ? "On" : "Off"}
      </QueuePill>
    </CellWrapper>
  );
};

export default AutomationCell;
