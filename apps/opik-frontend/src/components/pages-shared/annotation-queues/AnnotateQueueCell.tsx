import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

const AnnotateQueueCell = (context: CellContext<AnnotationQueue, string>) => {
  const queue = context.row.original;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center justify-end p-0"
    >
      <Link
        onClick={(event) => event.stopPropagation()}
        to="/$workspaceName/sme"
        params={{ workspaceName }}
        search={{
          queueId: queue.id,
        }}
        target="_blank"
      >
        <Button variant="tableLink" size="sm">
          Annotate queue
          <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
        </Button>
      </Link>
    </CellWrapper>
  );
};

export default AnnotateQueueCell;
