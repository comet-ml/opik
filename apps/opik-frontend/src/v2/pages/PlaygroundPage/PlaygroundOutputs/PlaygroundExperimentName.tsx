import React, { useCallback } from "react";
import { CircleAlert, ExternalLink, FlaskConical } from "lucide-react";

import { Button } from "@/ui/button";
import InlineEditableText from "@/shared/InlineEditableText/InlineEditableText";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  useCreatedExperiments,
  useExperimentName,
  usePromptIds,
  useSetExperimentName,
} from "@/store/PlaygroundStore";
import { buildExperimentName } from "@/lib/experiments";
import { useNavigateToExperiment } from "@/v2/pages-shared/experiments/useNavigateToExperiment";
import { toPlainDatasetId } from "@/utils/datasetVersionStorage";

interface PlaygroundExperimentNameProps {
  datasetId: string | null;
}

const PlaygroundExperimentName = ({
  datasetId,
}: PlaygroundExperimentNameProps) => {
  const createdExperiments = useCreatedExperiments();
  const experimentName = useExperimentName();
  const setExperimentName = useSetExperimentName();
  const promptIds = usePromptIds();
  const { navigate } = useNavigateToExperiment();

  const plainDatasetId = toPlainDatasetId(datasetId);
  const hasExperiments = createdExperiments.length > 0;
  const [firstPreview, ...restPreview] = experimentName
    ? promptIds.map((_, index) => buildExperimentName(experimentName, index))
    : [];

  const handleNavigateToExperiments = useCallback(() => {
    if (!createdExperiments.length || !plainDatasetId) return;
    navigate({
      experimentIds: createdExperiments.map((e) => e.id),
      datasetId: plainDatasetId,
    });
  }, [createdExperiments, plainDatasetId, navigate]);

  return (
    <div
      className="flex min-w-0 flex-1 items-center gap-1 pl-1"
      data-testid="playground-experiment-name"
    >
      <TooltipWrapper content="New experiment">
        <FlaskConical className="size-3.5 shrink-0 text-muted-slate lg:hidden" />
      </TooltipWrapper>
      <span className="hidden shrink-0 text-sm text-muted-slate lg:inline">
        New Experiment:
      </span>
      <div className="min-w-0" data-testid="playground-experiment-name-editor">
        <InlineEditableText
          value={experimentName ?? ""}
          placeholder="Auto-generated name"
          onChange={(value) => setExperimentName(value || null)}
          className="max-w-64 [&_input]:w-48"
        />
      </div>

      {firstPreview && (
        <div
          className="ml-2 flex min-w-0 shrink items-center gap-1 text-sm text-muted-slate"
          data-testid="playground-experiment-name-preview"
        >
          <span className="truncate">Creates: {firstPreview}</span>
          {restPreview.length > 0 && (
            <TooltipWrapper content={restPreview.join(", ")}>
              <span className="shrink-0 cursor-default underline">
                +{restPreview.length} more
              </span>
            </TooltipWrapper>
          )}
          <TooltipWrapper content="One experiment per output column.">
            <CircleAlert className="size-3.5 shrink-0" />
          </TooltipWrapper>
        </div>
      )}

      {hasExperiments && (
        <Button
          variant="ghost"
          size="sm"
          className="shrink-0 text-sm text-muted-slate"
          onClick={handleNavigateToExperiments}
          data-testid="playground-experiment-results-link"
        >
          <span>Experiment results</span>
          <ExternalLink className="ml-1 size-3.5 shrink-0" />
        </Button>
      )}
    </div>
  );
};

export default PlaygroundExperimentName;
