import React from "react";
import { CellContext } from "@tanstack/react-table";
import { ListTree } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import {
  useOutputByPromptDatasetItemId,
  useDatasetType,
  useExperimentIdByPromptId,
} from "@/store/PlaygroundStore";
import { DATASET_TYPE } from "@/types/datasets";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import PlaygroundOutputLoader from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import PlaygroundOutputScoresContainer from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundOutputScoresContainer";
import PlaygroundOutputAssertionStatus from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundOutputAssertionStatus";
import PlaygroundTestSuiteLastRunOutput from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundTestSuiteLastRunOutput";
import { usePlaygroundDataset } from "@/hooks/usePlaygroundDataset";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";
import { PLAYGROUND_PROMPT_COLORS } from "@/constants/llm";
import PlaygroundNoRunsYet from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundNoRunsYet";
import { generateTracesURL } from "@/lib/annotation-queues";
import { EXPERIMENT_TAB } from "@/lib/experiments";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";

interface PlaygroundOutputCellData {
  dataItemId: string;
}

interface CustomMeta {
  promptId: string;
  promptIndex: number;
}

const PlaygroundOutputCell: React.FunctionComponent<
  CellContext<PlaygroundOutputCellData, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { promptId, promptIndex } = (custom ?? {}) as CustomMeta;
  const originalRow = context.row.original;

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // One store subscription per cell, not five. Each of the fields below used to
  // come from its own hook, and every one of those hooks runs the *same*
  // selector, so a single streaming token re-ran it (dataset items x prompts x 5)
  // times. Reading the output object once and picking the fields off it is
  // equivalent — the defaults below are the ones those hooks applied — while
  // cutting the per-token selector work by 5x. The selector returns the stored
  // object reference, which only changes when this cell's own output changes, so
  // unrelated updates still don't re-render this cell.
  const output = useOutputByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );
  const value = output?.value ?? null;
  const isLoading = output?.isLoading ?? false;
  const stale = output?.stale ?? false;
  const traceId = output?.traceId ?? null;
  const selectedRuleIds = output?.selectedRuleIds;

  const datasetType = useDatasetType();
  const experimentId = useExperimentIdByPromptId(promptId);
  const { datasetId: versionedDatasetId } = usePlaygroundDataset();
  const plainDatasetId =
    parseDatasetVersionKey(versionedDatasetId)?.datasetId || versionedDatasetId;

  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;

  const activeProjectId = useActiveProjectId();

  const handleTraceLinkClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!traceId || !activeProjectId) return;

    // For dataset/experiment runs, open the trace on the experiment page's Logs tab, so the
    // surrounding list holds the experiment's traces. The main project Logs list filters to
    // source=sdk and would exclude them by design. The tab scopes itself to the experiment, so
    // only the tab and the trace to open need to travel in the URL.
    if (experimentId && plainDatasetId) {
      const search = new URLSearchParams({
        experiments: JSON.stringify([experimentId]),
        tab: EXPERIMENT_TAB.logs,
        tls_trace: traceId,
      }).toString();
      const basePath = import.meta.env.VITE_BASE_URL || "/";
      const normalizedBasePath =
        basePath === "/" ? "" : basePath.replace(/\/$/, "");
      const relativePath = `${workspaceName}/projects/${activeProjectId}/experiments/${plainDatasetId}/compare?${search}`;
      const url = new URL(
        `${normalizedBasePath}/${relativePath}`,
        window.location.origin,
      ).toString();
      window.open(url, "_blank");
      return;
    }

    const url = generateTracesURL(
      workspaceName,
      activeProjectId,
      "traces",
      traceId,
    );
    window.open(url, "_blank");
  };

  const hasOutput =
    !stale && (value !== null || isLoading || (isTestSuite && !!experimentId));
  const promptColor =
    PLAYGROUND_PROMPT_COLORS[
      (promptIndex ?? 0) % PLAYGROUND_PROMPT_COLORS.length
    ];
  const noRunsColor = isTestSuite ? "var(--click-blue)" : promptColor.bg;

  const renderContent = () => {
    if (isLoading && !value) {
      return <PlaygroundOutputLoader />;
    }

    return <MarkdownPreview>{value}</MarkdownPreview>;
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="flex pt-5"
    >
      {hasOutput ? (
        <div className="group relative flex size-full flex-col">
          {traceId && activeProjectId && (
            <TooltipWrapper content="Click to open original trace">
              <Button
                size="icon-xs"
                variant="outline"
                onClick={handleTraceLinkClick}
                className="absolute right-1 top-1 z-10 hidden group-hover:flex"
              >
                <ListTree />
              </Button>
            </TooltipWrapper>
          )}
          <div className="mb-2 min-h-[var(--cell-top-height)]">
            {isTestSuite ? (
              <PlaygroundOutputAssertionStatus
                experimentId={experimentId}
                datasetItemId={originalRow.dataItemId}
                datasetId={plainDatasetId || ""}
              />
            ) : (
              <PlaygroundOutputScoresContainer
                traceId={traceId}
                selectedRuleIds={selectedRuleIds}
                stale={stale}
              />
            )}
          </div>
          <div className="flex-1 overflow-y-auto">
            {isTestSuite ? (
              <PlaygroundTestSuiteLastRunOutput
                experimentId={experimentId}
                datasetItemId={originalRow.dataItemId}
                datasetId={plainDatasetId || ""}
              />
            ) : (
              renderContent()
            )}
          </div>
        </div>
      ) : (
        <PlaygroundNoRunsYet color={noRunsColor} />
      )}
    </CellWrapper>
  );
};

export default PlaygroundOutputCell;
