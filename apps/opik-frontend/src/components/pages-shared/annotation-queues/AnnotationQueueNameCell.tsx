import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Zap } from "lucide-react";

import {
  AnnotationQueue,
  ANNOTATION_QUEUE_TYPE,
} from "@/types/annotation-queues";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const AnnotationQueueNameCell: React.FC<
  CellContext<AnnotationQueue, string>
> = (context) => {
  const queue = context.row.original;
  const isDynamic = queue.queue_type === ANNOTATION_QUEUE_TYPE.DYNAMIC;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {isDynamic && (
        <TooltipWrapper content="Dynamic queue - automatically adds matching items">
          <Zap className="size-4 shrink-0 text-amber-500" />
        </TooltipWrapper>
      )}
      <span className="truncate">{queue.name}</span>
    </CellWrapper>
  );
};

export default AnnotationQueueNameCell;
