import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

const ShowItemsCell = (context: CellContext<AnnotationQueue, string>) => {
  const queue = context.row.original;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // TODO lala check filter struture
  const getSearchParams = () => {
    if (queue.scope === ANNOTATION_QUEUE_SCOPE.TRACE) {
      return {
        traces_filters: [
          {
            id: "annotation_queue_filter",
            field: "annotation_queue_id", // This might need to be adjusted based on actual backend field
            type: "string",
            operator: "=",
            value: queue.id,
          },
        ],
      };
    } else {
      return {
        type: "threads",
        threads_filters: [
          {
            id: "annotation_queue_filter",
            field: "annotation_queue_id", // This might need to be adjusted based on actual backend field
            type: "string",
            operator: "=",
            value: queue.id,
          },
        ],
      };
    }
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center justify-end p-0"
    >
      <Link
        onClick={(event) => event.stopPropagation()}
        to="/$workspaceName/projects/$projectId/traces"
        params={{
          workspaceName,
          projectId: queue.project_id,
        }}
        search={getSearchParams()}
      >
        <Button variant="tableLink" size="sm">
          Show items
          <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
        </Button>
      </Link>
    </CellWrapper>
  );
};

export default ShowItemsCell;
