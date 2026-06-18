import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { FEEDBACK_SCORE_SOURCE_MAP } from "@/lib/feedback-scores";
import { getIsParentFeedbackScoreRow } from "../utils";
import { cn } from "@/lib/utils";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/shared/ResourceLink/ResourceLink";

const SourceCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;
  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(row);

  const { data: queue } = useAnnotationQueueById(
    { annotationQueueId: row.source_queue_id ?? "" },
    { enabled: !!row.source_queue_id },
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {row.source_queue_id && queue?.name ? (
        <ResourceLink
          id={row.source_queue_id}
          name={queue.name}
          resource={RESOURCE_TYPE.annotationQueue}
        />
      ) : (
        <span
          className={cn("truncate", {
            "text-light-slate": isParentFeedbackScoreRow,
          })}
        >
          {row.source_queue_id
            ? "<deleted queue>"
            : FEEDBACK_SCORE_SOURCE_MAP[row.source]}
        </span>
      )}
    </CellWrapper>
  );
};

export default SourceCell;
