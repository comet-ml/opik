import React from "react";

import { cn } from "@/lib/utils";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import {
  useOutputLoadingByPromptDatasetItemId,
  useOutputStaleStatusByPromptDatasetItemId,
  useOutputValueByPromptDatasetItemId,
} from "@/store/PlaygroundStore";

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
      <div className="comet-body-s min-h-52 rounded-lg border bg-background p-3">
        {renderContent()}
      </div>
    </div>
  );
};

export default PlaygroundOutput;
