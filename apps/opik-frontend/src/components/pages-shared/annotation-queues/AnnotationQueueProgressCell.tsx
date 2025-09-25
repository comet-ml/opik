import { CellContext } from "@tanstack/react-table";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { AnnotationQueue } from "@/types/annotation-queues";

const AnnotationQueueProgressCell = (
  context: CellContext<AnnotationQueue, unknown>,
) => {
  const queue = context.row.original;
  const { items_count: itemsCount, reviewers } = queue;

  if (!reviewers || reviewers.length === 0) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <div className="flex size-full items-center overflow-hidden py-0.5">
          <span className="comet-body-s text-muted-foreground">-</span>
        </div>
      </CellWrapper>
    );
  }

  const averageProgress =
    reviewers.reduce((sum, reviewer) => sum + reviewer.status, 0) /
    reviewers.length;
  const progressPercentage =
    itemsCount > 0 ? Math.round((averageProgress / itemsCount) * 100) : 0;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <HoverCard>
        <HoverCardTrigger asChild>
          <div className="flex size-full cursor-pointer items-center overflow-hidden py-0.5">
            <span className="comet-body-s">
              {Math.round(averageProgress)}/{itemsCount} ({progressPercentage}%)
            </span>
          </div>
        </HoverCardTrigger>
        <HoverCardContent className="w-64">
          <div className="space-y-2">
            <h4 className="comet-title-xs">Progress by Reviewer</h4>
            <div className="space-y-1">
              {reviewers.map((reviewer) => {
                const reviewerProgress =
                  itemsCount > 0
                    ? Math.round((reviewer.status / itemsCount) * 100)
                    : 0;
                return (
                  <div
                    key={reviewer.username}
                    className="flex items-center justify-between"
                  >
                    <span className="comet-body-s">{reviewer.username}</span>
                    <span className="comet-body-s text-muted-foreground">
                      {reviewer.status}/{itemsCount} ({reviewerProgress}%)
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </HoverCardContent>
      </HoverCard>
    </CellWrapper>
  );
};

export default AnnotationQueueProgressCell;
