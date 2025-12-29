import React from "react";
import { CellContext } from "@tanstack/react-table";
import { ListTree } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {
  useOutputLoadingByPromptDatasetItemId,
  useOutputStaleStatusByPromptDatasetItemId,
  useOutputValueByPromptDatasetItemId,
  useSelectedRuleIdsByPromptDatasetItemId,
  useTraceIdByPromptDatasetItemId,
} from "@/store/PlaygroundStore";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import PlaygroundOutputScoresContainer from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputScores/PlaygroundOutputScoresContainer";
import { cn } from "@/lib/utils";
import { generateTracesURL } from "@/lib/annotation-queues";
import useAppStore from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";

interface PlaygroundOutputCellData {
  dataItemId: string;
}

interface CustomMeta {
  promptId: string;
}

const PlaygroundOutputCell: React.FunctionComponent<
  CellContext<PlaygroundOutputCellData, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { promptId } = (custom ?? {}) as CustomMeta;
  const originalRow = context.row.original;

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const value = useOutputValueByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const isLoading = useOutputLoadingByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const stale = useOutputStaleStatusByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const traceId = useTraceIdByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const selectedRuleIds = useSelectedRuleIdsByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const { data: playgroundProject } = useProjectByName(
    {
      projectName: PLAYGROUND_PROJECT_NAME,
    },
    {
      enabled: !!traceId && !!workspaceName,
    },
  );

  const handleTraceLinkClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (traceId && playgroundProject?.id) {
      const url = generateTracesURL(
        workspaceName,
        playgroundProject.id,
        "traces",
        traceId,
      );
      window.open(url, "_blank");
    }
  };

  const renderContent = () => {
    if (isLoading && !value) {
      return <PlaygroundOutputLoader />;
    }

    return (
      <MarkdownPreview
        className={cn({
          "text-muted-gray dark:text-foreground": stale,
        })}
      >
        {value}
      </MarkdownPreview>
    );
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="flex pt-5"
    >
      <div className="group relative flex size-full flex-col">
        {traceId && playgroundProject?.id && (
          <TooltipWrapper content="Click to open original trace">
            <Button
              size="icon-xs"
              variant="outline"
              onClick={handleTraceLinkClick}
              className="absolute right-1 top-1 hidden group-hover:flex"
            >
              <ListTree />
            </Button>
          </TooltipWrapper>
        )}
        <div className="mb-2 min-h-[var(--cell-top-height)]">
          <PlaygroundOutputScoresContainer
            traceId={traceId}
            selectedRuleIds={selectedRuleIds}
            stale={stale}
          />
        </div>
        <div className="flex-1 overflow-y-auto">{renderContent()}</div>
      </div>
    </CellWrapper>
  );
};

export default PlaygroundOutputCell;
