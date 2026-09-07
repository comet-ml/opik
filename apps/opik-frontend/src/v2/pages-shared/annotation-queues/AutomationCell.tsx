import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Zap, ZapOff } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
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
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag size={tagSize} variant="gray" className="flex items-center gap-1">
        {enabled ? (
          <Zap className="size-3 shrink-0 text-muted-gray" />
        ) : (
          <ZapOff className="size-3 shrink-0 text-muted-gray" />
        )}
        {enabled ? "On" : "Off"}
      </Tag>
    </CellWrapper>
  );
};

export default AutomationCell;
