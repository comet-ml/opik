import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import { MessageSquareMore } from "lucide-react";
import FeedbackScoreReasonTooltip from "@/components/shared/FeedbackScoreTag/FeedbackScoreReasonTooltip";

const CompareExperimentsFeedbackScoreCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { feedbackKey } = (custom ?? {}) as CustomMeta;

  const renderContent = (item: ExperimentItem | undefined) => {
    const feedbackScore = item?.feedback_scores?.find(
      (f) => f.name === feedbackKey,
    );

    if (!feedbackScore) {
      return "-";
    }

    return (
      <div className="flex h-4 w-full items-center justify-end gap-1">
        <div className="truncate">{feedbackScore.value}</div>
        {feedbackScore.reason && (
          <FeedbackScoreReasonTooltip
            reason={feedbackScore.reason}
            lastUpdatedAt={feedbackScore.last_updated_at}
            lastUpdatedBy={feedbackScore.last_updated_by}
          >
            <MessageSquareMore className="size-3.5 shrink-0 text-light-slate" />
          </FeedbackScoreReasonTooltip>
        )}
      </div>
    );
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};

export default CompareExperimentsFeedbackScoreCell;
