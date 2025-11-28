import React, { useMemo } from "react";
import useAppStore from "@/store/AppStore";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import { FEEDBACK_SCORE_TYPE, TraceFeedbackScore } from "@/types/traces";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import { sortBy } from "lodash";
import { Button } from "@/components/ui/button";
import { Link } from "@tanstack/react-router";
import { ExternalLink, InfoIcon } from "lucide-react";
import AnnotateRow from "../TraceDetailsPanel/TraceAnnotateViewer/AnnotateRow";
import { cn } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { UpdateFeedbackScoreData } from "../TraceDetailsPanel/TraceAnnotateViewer/types";

type FeedbackScoresEditorProps = {
  feedbackScores: TraceFeedbackScore[];
  className?: string;
  onUpdateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  onDeleteFeedbackScore: (
    name: string,
    author?: string,
    spanId?: string,
  ) => void;
  header?: React.ReactNode;
  footer?: React.ReactNode;
  feedbackDefinitionNames?: string[];
};

type FeedbackScoreRow = {
  name: string;
  feedbackDefinition?: FeedbackDefinition;
  feedbackScore?: TraceFeedbackScore;
};

type FeedbackScoresEditorFooterProps = {
  entityCopy: string;
};

type FeedbackScoresEditorHeaderProps = {
  isTrace?: boolean;
  isThread?: boolean;
};

const getTitleOfScores = ({
  isThread,
  isTrace,
}: {
  isThread?: boolean;
  isTrace?: boolean;
}): string => {
  if (isThread) {
    return "Your thread scores";
  }
  if (isTrace) {
    return "Your trace scores";
  }
  return "Your span scores";
};

const FeedbackScoresEditorHeader: React.FC<FeedbackScoresEditorHeaderProps> = ({
  isTrace = false,
  isThread = false,
}) => {
  const title = getTitleOfScores({ isThread, isTrace });
  return (
    <div className="flex items-center gap-1 pb-2">
      <span className="comet-body-s-accented truncate">{title}</span>
      <ExplainerIcon {...EXPLAINERS_MAP[EXPLAINER_ID.what_is_human_review]} />
    </div>
  );
};

const FeedbackScoresEditorFooter: React.FC<FeedbackScoresEditorFooterProps> = ({
  entityCopy,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="comet-body-xs flex gap-1.5 pt-4 text-light-slate">
      <div className="pt-[3px]">
        <InfoIcon className="size-3" />
      </div>
      <div className="leading-relaxed">
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
        to annotate your {entityCopy}.
      </div>
    </div>
  );
};

const FeedbackScoresEditor = ({
  feedbackScores,
  onUpdateFeedbackScore,
  onDeleteFeedbackScore,
  className,
  header,
  footer,
  feedbackDefinitionNames,
}: FeedbackScoresEditorProps) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const feedbackScoresUI: TraceFeedbackScore[] = useMemo(
    () =>
      feedbackScores.filter((f) => f.source === FEEDBACK_SCORE_TYPE.ui) || [],
    [feedbackScores],
  );

  const feedbackDefinitions: FeedbackDefinition[] = useMemo(() => {
    const allDefinitions = feedbackDefinitionsData?.content || [];

    if (feedbackDefinitionNames && feedbackDefinitionNames.length > 0) {
      return allDefinitions.filter((definition) =>
        feedbackDefinitionNames.includes(definition.name),
      );
    }

    return allDefinitions;
  }, [feedbackDefinitionsData?.content, feedbackDefinitionNames]);

  const rows: FeedbackScoreRow[] = useMemo(() => {
    return sortBy(
      [
        ...feedbackDefinitions.map((feedbackDefinition) => {
          const feedbackScore = feedbackScoresUI.find(
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
  }, [feedbackDefinitions, feedbackScoresUI]);

  return (
    <div className={cn("flex flex-col px-6", className)}>
      {header}
      <div className="grid max-w-full grid-cols-[minmax(0,5fr)_minmax(0,5fr)__36px_30px] border-b border-border empty:border-transparent">
        {rows.map((row) => (
          <AnnotateRow
            key={row.name}
            name={row.name}
            feedbackDefinition={row.feedbackDefinition}
            feedbackScore={row.feedbackScore}
            onUpdateFeedbackScore={onUpdateFeedbackScore}
            onDeleteFeedbackScore={onDeleteFeedbackScore}
          />
        ))}
      </div>
      {footer}
    </div>
  );
};

FeedbackScoresEditor.Header = FeedbackScoresEditorHeader;
FeedbackScoresEditor.Footer = FeedbackScoresEditorFooter;

export default FeedbackScoresEditor;
