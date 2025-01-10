import React, { useMemo, useState } from "react";
import sortBy from "lodash/sortBy";
import { Plus } from "lucide-react";
import { useHotkeys } from "react-hotkeys-hook";

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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";
import AnnotateRow from "./AnnotateRow";

type TraceAnnotateViewerProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
  annotateOpen: boolean;
  setAnnotateOpen: (open: boolean) => void;
};

const HOTKEYS = ["Esc"];

const TraceAnnotateViewer: React.FunctionComponent<
  TraceAnnotateViewerProps
> = ({ data, spanId, traceId, annotateOpen, setAnnotateOpen }) => {
  const [feedbackDefinitionDialogOpen, setFeedbackDefinitionDialogOpen] =
    useState(false);

  useHotkeys(
    "Escape",
    (keyboardEvent: KeyboardEvent) => {
      if (!annotateOpen) return;
      keyboardEvent.stopPropagation();
      switch (keyboardEvent.code) {
        case "Escape":
          setAnnotateOpen(false);
          break;
      }
    },
    [annotateOpen],
  );

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

  return (
    <div className="size-full max-w-full overflow-auto p-6">
      <div className="min-w-60 max-w-full overflow-x-hidden">
        <div className="flex w-full items-center justify-between gap-2 overflow-x-hidden">
          <div className="flex items-center gap-2 overflow-x-hidden">
            <div className="comet-title-m truncate">Annotate</div>
          </div>
          <TooltipWrapper content="Close annotate" hotkeys={HOTKEYS}>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setAnnotateOpen(false)}
            >
              Close
            </Button>
          </TooltipWrapper>
        </div>
        <div className="mt-4 flex flex-col gap-4">
          <div className="grid max-w-full grid-cols-[minmax(0,5fr)_minmax(0,10fr)_minmax(0,2fr)] border-t border-border">
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
          <div className="flex">
            <Button
              size="sm"
              variant="secondary"
              onClick={() => setFeedbackDefinitionDialogOpen(true)}
            >
              <Plus className="mr-2 size-4 " />
              Add feedback definition
            </Button>

            <AddEditFeedbackDefinitionDialog
              open={feedbackDefinitionDialogOpen}
              setOpen={setFeedbackDefinitionDialogOpen}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default TraceAnnotateViewer;
