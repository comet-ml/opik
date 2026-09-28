import React, { useCallback } from "react";
import { CircleAlert, FlaskConical } from "lucide-react";

import InlineEditableText from "@/shared/InlineEditableText/InlineEditableText";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  useExperimentName,
  usePromptIds,
  useSetExperimentName,
} from "@/store/PlaygroundStore";
import { buildExperimentName } from "@/lib/experiments";

const PlaygroundExperimentName = () => {
  const experimentName = useExperimentName();
  const setExperimentName = useSetExperimentName();
  const promptIds = usePromptIds();

  const [firstPreview, ...restPreview] = experimentName
    ? promptIds.map((_, index) => buildExperimentName(experimentName, index))
    : [];

  const handleChangeName = useCallback(
    (value: string) => {
      const next = value || null;
      if (next === experimentName) return;
      setExperimentName(next);
    },
    [experimentName, setExperimentName],
  );

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
          onChange={handleChangeName}
          className="max-w-64 [&_input]:w-48"
          alwaysShowEditIcon
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
    </div>
  );
};

export default PlaygroundExperimentName;
