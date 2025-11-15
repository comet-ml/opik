import React from "react";
import { ListTree } from "lucide-react";

import { cn } from "@/lib/utils";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import {
  useOutputLoadingByPromptDatasetItemId,
  useOutputStaleStatusByPromptDatasetItemId,
  useOutputValueByPromptDatasetItemId,
  useTraceIdByPromptDatasetItemId,
} from "@/store/PlaygroundStore";
import { generateTracesURL } from "@/lib/annotation-queues";
import useAppStore from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";

interface PlaygroundOutputProps {
  promptId: string;
  totalOutputs: number;
}

const PlaygroundOutput = ({
  promptId,
  totalOutputs,
}: PlaygroundOutputProps) => {
  const value = useOutputValueByPromptDatasetItemId(promptId);
  const isLoading = useOutputLoadingByPromptDatasetItemId(promptId);
  const stale = useOutputStaleStatusByPromptDatasetItemId(promptId);
  const traceId = useTraceIdByPromptDatasetItemId(promptId);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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

  const outputLabel = totalOutputs === 1 ? "Output" : "Outputs";

  return (
    <div className="size-full min-w-[var(--min-prompt-width)]">
      <p className="comet-body-s-accented my-3">{outputLabel}</p>
      <div className="comet-body-s group relative min-h-52 rounded-lg border bg-background p-3">
        {traceId && playgroundProject?.id && (
          <TooltipWrapper content="Click to open original trace">
            <Button
              size="icon-xs"
              variant="outline"
              onClick={handleTraceLinkClick}
              className="absolute right-4 top-4 hidden group-hover:flex"
            >
              <ListTree />
            </Button>
          </TooltipWrapper>
        )}
        {renderContent()}
      </div>
    </div>
  );
};

export default PlaygroundOutput;
