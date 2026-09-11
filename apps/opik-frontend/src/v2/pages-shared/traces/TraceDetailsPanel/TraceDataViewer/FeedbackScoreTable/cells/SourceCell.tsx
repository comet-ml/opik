import { ReactNode } from "react";
import { CellContext } from "@tanstack/react-table";
import { Loader2 } from "lucide-react";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { getIsParentFeedbackScoreRow } from "../utils";
import { cn } from "@/lib/utils";
import useFeedbackScoreSourceLabel from "@/hooks/useFeedbackScoreSourceLabel";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/shared/ResourceLink/ResourceLink";

const SourceCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;
  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(row);

  const { isLoading, queueId, label } = useFeedbackScoreSourceLabel(
    row.source_queue_id,
    row.source,
  );

  let content: ReactNode;
  if (isLoading) {
    content = <Loader2 className="size-3.5 animate-spin text-light-slate" />;
  } else if (queueId) {
    content = (
      <ResourceLink
        id={queueId}
        name={label}
        resource={RESOURCE_TYPE.annotationQueue}
      />
    );
  } else {
    content = (
      <span
        className={cn("truncate", {
          "text-light-slate": isParentFeedbackScoreRow,
        })}
      >
        {label}
      </span>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {content}
    </CellWrapper>
  );
};

export default SourceCell;
