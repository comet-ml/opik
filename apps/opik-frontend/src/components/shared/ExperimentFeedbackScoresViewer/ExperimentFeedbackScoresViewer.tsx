import React from "react";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import AddFeedbackScorePopover from "@/components/shared/ExperimentFeedbackScoresViewer/AddFeedbackScorePopover";
import ExpandableSection from "../ExpandableSection/ExpandableSection";
import { PenLine } from "lucide-react";
import { Separator } from "@/components/ui/separator";
import { sortBy } from "lodash";

type ExperimentFeedbackScoresViewerProps = {
  feedbackScores: TraceFeedbackScore[];
  traceId: string;
  spanId?: string;
  sectionIdx: number;
};

const ExperimentFeedbackScoresViewer: React.FunctionComponent<
  ExperimentFeedbackScoresViewerProps
> = ({ feedbackScores = [], traceId, spanId, sectionIdx }) => {
  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const handleDeleteFeedbackScore = (name: string) => {
    feedbackScoreDeleteMutation.mutate({
      traceId,
      spanId,
      name,
    });
  };

  return (
    <ExpandableSection
      icon={<PenLine className="size-4" />}
      title="Feedback scores"
      queryParamName="expandedFeedbackScoresSections"
      sectionIdx={sectionIdx}
      count={feedbackScores.length}
    >
      <div className="flex w-full flex-wrap items-center gap-2 px-2 pb-4">
        <AddFeedbackScorePopover
          feedbackScores={feedbackScores}
          traceId={traceId}
          spanId={spanId}
        />
        {feedbackScores.length !== 0 && (
          <Separator orientation="vertical" className="h-4" />
        )}
        {sortBy(feedbackScores, "name").map((feedbackScore) => (
          <FeedbackScoreTag
            key={feedbackScore.name + feedbackScore.value}
            label={feedbackScore.name}
            value={feedbackScore.value}
            reason={feedbackScore.reason}
            lastUpdatedAt={feedbackScore.last_updated_at}
            lastUpdatedBy={feedbackScore.last_updated_by}
            onDelete={handleDeleteFeedbackScore}
            className="max-w-full"
          />
        ))}
      </div>
    </ExpandableSection>
  );
};

export default ExperimentFeedbackScoresViewer;
