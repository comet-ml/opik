import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { AnnotationQueue } from "@/types/annotation-queues";
import AnnotationQueueProgress from "@/components/pages-shared/annotation-queues/AnnotationQueueProgress";

const AnnotationQueueProgressCell = (
  context: CellContext<AnnotationQueue, unknown>,
) => {
  const queue = context.row.original;
  const { reviewers } = queue;

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

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <AnnotationQueueProgress annotationQueue={queue}>
        {({ averageProgress, progressPercentage, itemsCount }) => (
          <div className="flex size-full cursor-pointer items-center overflow-hidden py-0.5">
            <span className="comet-body-s">
              {averageProgress}/{itemsCount} ({progressPercentage}%)
            </span>
          </div>
        )}
      </AnnotationQueueProgress>
    </CellWrapper>
  );
};

export default AnnotationQueueProgressCell;
