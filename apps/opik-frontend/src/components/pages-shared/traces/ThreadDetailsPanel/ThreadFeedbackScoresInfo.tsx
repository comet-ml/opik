import React from "react";
import { TraceFeedbackScore } from "@/types/traces";
import { InfoIcon } from "lucide-react";
import { Button } from "@/components/ui/button";

interface ThreadFeedbackScoresInfoProps {
  feedbackScores: TraceFeedbackScore[];
  onAddHumanReview: () => void;
}

const ThreadFeedbackScoresInfo: React.FC<ThreadFeedbackScoresInfoProps> = ({
  feedbackScores,
  onAddHumanReview,
}) => {
  if (!feedbackScores || feedbackScores.length === 0) {
    return null;
  }

  //   const scoreDocsLink = buildDocsUrl("/tracing/annotate_traces");

  return (
    <div className="comet-body-xs mt-2 flex gap-1.5 py-2 text-light-slate">
      <div className="pt-[3px]">
        <InfoIcon className="size-3" />
      </div>
      <div className="leading-relaxed">
        {/* Use the SDK or Online evaluation rules to
        <Button
          size="sm"
          variant="link"
          className="comet-body-xs inline-flex h-auto gap-0.5 px-1"
          asChild
        >
          <a href={scoreDocsLink} target="_blank" rel="noopener noreferrer">
            automatically score
            <ExternalLink className="size-3" />
          </a>
        </Button>
        your threads, or manually annotate your thread with */}
        Use the SDK or manually annotate your thread with
        <Button
          size="sm"
          variant="link"
          className="comet-body-xs inline-flex h-auto gap-0.5 px-1"
          onClick={onAddHumanReview}
        >
          human review
        </Button>
        .
      </div>
    </div>
  );
};

export default ThreadFeedbackScoresInfo;
