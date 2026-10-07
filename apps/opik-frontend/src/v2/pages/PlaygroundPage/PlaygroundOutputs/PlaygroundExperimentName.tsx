import React, { useCallback } from "react";
import { CircleAlert, FlaskConical } from "lucide-react";
import sortBy from "lodash/sortBy";

import InlineEditableText from "@/shared/InlineEditableText/InlineEditableText";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  useExperimentName,
  useLastRun,
  usePromptIds,
  useSetExperimentName,
} from "@/store/PlaygroundStore";
import { buildExperimentName } from "@/lib/experiments";
import useRenameLastRunMutation from "@/api/playground/useRenameLastRunMutation";
import useLastRunExperiments from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useLastRunExperiments";
import { usePermissions } from "@/contexts/PermissionsContext";

type PlaygroundExperimentNameProps = {
  datasetId?: string;
};

const PlaygroundExperimentName = ({
  datasetId,
}: PlaygroundExperimentNameProps) => {
  const experimentName = useExperimentName();
  const setExperimentName = useSetExperimentName();
  const promptIds = usePromptIds();
  const {
    permissions: { canCreateExperiments },
  } = usePermissions();
  const lastRun = useLastRun(canCreateExperiments ? datasetId : undefined);
  const {
    mutate: renameLastRun,
    isPending: isRenaming,
    variables: pendingRename,
  } = useRenameLastRunMutation();

  const shownName =
    isRenaming && pendingRename ? pendingRename.name : experimentName;
  const previewIndexes = lastRun
    ? sortBy(lastRun.experiments, "index").map((e) => e.index)
    : promptIds.map((_, index) => index);
  const [firstPreview, ...restPreview] = shownName
    ? previewIndexes.map((index) => buildExperimentName(shownName, index))
    : [];
  const lastRunExperiments = useLastRunExperiments(lastRun);
  const serverNames =
    lastRun && !shownName
      ? lastRunExperiments.flatMap((e) => (e.name ? [e.name] : []))
      : [];

  const handleChangeName = useCallback(
    (value: string) => {
      const next = value || null;
      if (isRenaming || next === experimentName) return;

      if (lastRun && next) {
        renameLastRun({ lastRun, name: next });
        return;
      }

      setExperimentName(next);
    },
    [isRenaming, experimentName, lastRun, renameLastRun, setExperimentName],
  );

  return (
    <div
      className="flex min-w-0 flex-1 items-center gap-1 pl-1"
      data-testid="playground-experiment-name"
    >
      <TooltipWrapper content={lastRun ? "Last run" : "New experiment"}>
        <FlaskConical className="size-3.5 shrink-0 text-muted-slate lg:hidden" />
      </TooltipWrapper>
      <span className="hidden shrink-0 text-sm text-muted-slate lg:inline">
        {lastRun ? "Last run:" : "New Experiment:"}
      </span>
      <div className="min-w-0" data-testid="playground-experiment-name-editor">
        <InlineEditableText
          value={shownName ?? ""}
          placeholder={serverNames[0] ?? "Auto-generated name"}
          onChange={handleChangeName}
          className="max-w-64 [&_input]:w-48"
          alwaysShowEditIcon
        />
      </div>
      {serverNames.length > 1 && (
        <TooltipWrapper content={serverNames.join(", ")}>
          <span
            className="shrink-0 cursor-default text-sm text-muted-slate underline"
            data-testid="playground-experiment-name-more"
          >
            +{serverNames.length - 1} more
          </span>
        </TooltipWrapper>
      )}

      {firstPreview && (
        <div
          className="ml-2 flex min-w-0 shrink items-center gap-1 text-sm text-muted-slate"
          data-testid="playground-experiment-name-preview"
        >
          <span className="truncate">
            {lastRun ? "Created" : "Creates"}: {firstPreview}
          </span>
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
