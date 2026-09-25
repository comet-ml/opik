import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import QueuePill from "@/v2/pages-shared/annotation-queues/QueuePill";
import { AnnotationQueue } from "@/types/annotation-queues";

/**
 * What a queue collects. Shares QueuePill with the Automation and Source columns so the three read as
 * the same kind of object.
 */
const ScopeCell: React.FC<CellContext<AnnotationQueue, string>> = (context) => (
  <CellWrapper
    metadata={context.column.columnDef.meta}
    tableMetadata={context.table.options.meta}
  >
    <QueuePill>{context.getValue()}</QueuePill>
  </CellWrapper>
);

export default ScopeCell;
