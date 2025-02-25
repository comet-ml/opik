import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import { ExternalLink } from "lucide-react";

import {
  FEEDBACK_SCORE_TYPE,
  Span,
  Trace,
  TraceFeedbackScore,
} from "@/types/traces";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import AnnotateRow from "./AnnotateRow";
import { LastSectionValue } from "../TraceDetailsPanel";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { Link } from "@tanstack/react-router";
import LastSectionLayout from "../LastSectionLayout";

type TraceAnnotateViewerProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
  lastSection?: LastSectionValue | null;
  setLastSection: (v: LastSectionValue | null) => void;
};

const TraceAnnotateViewer: React.FunctionComponent<
  TraceAnnotateViewerProps
> = ({ data, spanId, traceId, lastSection, setLastSection }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const feedbackScores: TraceFeedbackScore[] = useMemo(
    () =>
      data.feedback_scores?.filter(
        (f) => f.source === FEEDBACK_SCORE_TYPE.ui,
      ) || [],
    [data.feedback_scores],
  );

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(
    () => feedbackDefinitionsData?.content || [],
    [feedbackDefinitionsData?.content],
  );

  const rows: {
    name: string;
    feedbackDefinition?: FeedbackDefinition;
    feedbackScore?: TraceFeedbackScore;
  }[] = useMemo(() => {
    return sortBy(
      [
        ...feedbackDefinitions.map((feedbackDefinition) => {
          const feedbackScore = feedbackScores.find(
            (feedbackScore) => feedbackScore.name === feedbackDefinition.name,
          );

          return {
            feedbackDefinition,
            feedbackScore,
            name: feedbackDefinition.name,
          };
        }),
      ],
      "name",
    );
  }, [feedbackDefinitions, feedbackScores]);

  const hasFeedbackScores = Boolean(data.feedback_scores?.length);

  return (
    <LastSectionLayout
      title="Feedback scores"
      closeTooltipContent="Close annotate"
      setLastSection={setLastSection}
      lastSection={lastSection}
    >
      {hasFeedbackScores && (
        <div className="flex flex-wrap gap-2 px-6 pb-2 pt-4">
          {data.feedback_scores?.map((score) => (
            <FeedbackScoreTag
              key={score.name}
              label={score.name}
              value={score.value}
              reason={score.reason}
            />
          ))}
        </div>
      )}
      <div className="mt-4 flex flex-col px-6">
        <div className="comet-body-s-accented pb-2">Human review</div>
        <div className="grid max-w-full grid-cols-[minmax(0,5fr)_minmax(0,5fr)__36px_30px] border-b border-border empty:border-transparent">
          {rows.map((row) => (
            <AnnotateRow
              key={row.name}
              name={row.name}
              feedbackDefinition={row.feedbackDefinition}
              feedbackScore={row.feedbackScore}
              spanId={spanId}
              traceId={traceId}
            />
          ))}
        </div>
        <div className="comet-body-xs pt-4 text-light-slate">
          Set up
          <Button
            size="sm"
            variant="link"
            className="comet-body-xs inline-flex h-auto gap-0.5 px-1"
            asChild
          >
            <Link
              to="/$workspaceName/configuration"
              params={{ workspaceName }}
              search={{
                tab: "feedback-definitions",
              }}
              target="_blank"
              rel="noopener noreferrer"
            >
              custom human review scores
              <ExternalLink className="size-3" />
            </Link>
          </Button>
          to annotate your traces.
        </div>
      </div>
    </LastSectionLayout>
  );
};

export default TraceAnnotateViewer;
