import React from "react";
import { Clock, Coins } from "lucide-react";

import { cn } from "@/lib/utils";
import PlaygroundOutputLoader from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import {
  useOutputLoadingByPromptDatasetItemId,
  useOutputStaleStatusByPromptDatasetItemId,
  useOutputValueByPromptDatasetItemId,
  useOutputByPromptDatasetItemId,
} from "@/store/PlaygroundStore";
import { getAlphabetLetter } from "@/lib/utils";
import { PLAYGROUND_PROMPT_COLORS } from "@/constants/llm";
import usePromptModelDisplay from "@/v2/pages/PlaygroundPage/usePromptModelDisplay";
import PlaygroundNoRunsYet from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundNoRunsYet";
import PlaygroundRunButton from "@/v2/pages/PlaygroundPage/PlaygroundRunButton";

interface PlaygroundPromptOutputProps {
  promptId: string;
  promptIndex: number;
  onRun?: () => void;
  onStop?: () => void;
}

const PlaygroundPromptOutput = ({
  promptId,
  promptIndex,
  onRun,
  onStop,
}: PlaygroundPromptOutputProps) => {
  const value = useOutputValueByPromptDatasetItemId(promptId);
  const isLoading = useOutputLoadingByPromptDatasetItemId(promptId);
  const stale = useOutputStaleStatusByPromptDatasetItemId(promptId);
  const output = useOutputByPromptDatasetItemId(promptId);

  const usage = output?.usage;

  const { ProviderIcon, modelLabel } = usePromptModelDisplay(
    usage?.provider,
    usage?.model,
  );

  const hasOutput = value !== null || isLoading;

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

  const promptColor =
    PLAYGROUND_PROMPT_COLORS[promptIndex % PLAYGROUND_PROMPT_COLORS.length];
  const letter = getAlphabetLetter(promptIndex);

  return (
    <div className="flex min-w-[var(--min-prompt-width)] max-w-[var(--max-prompt-width)] flex-1 flex-col border-r">
      {onRun && onStop && (
        <PlaygroundRunButton
          promptId={promptId}
          onRun={onRun}
          onStop={onStop}
        />
      )}
      {hasOutput ? (
        <div className="flex-1 bg-background p-4">
          <div className="mb-3 flex items-center gap-5">
            <span className="flex items-center gap-2">
              <span
                className="inline-block size-3 rounded-sm"
                style={{ backgroundColor: promptColor.bg }}
              />
              <span className="comet-body-s-accented">Output {letter}</span>
            </span>
            {modelLabel && ProviderIcon && (
              <span className="flex items-center gap-1 text-muted-gray">
                <ProviderIcon className="size-3.5 shrink-0" />
                <span className="comet-body-xs">{modelLabel}</span>
              </span>
            )}
            {usage?.duration != null && (
              <span className="flex items-center gap-1 text-muted-gray">
                <Clock className="size-3.5 shrink-0" />
                <span className="comet-body-xs">
                  {usage.duration.toFixed(1)}s
                </span>
              </span>
            )}
            {usage?.totalTokens != null && (
              <span className="flex items-center gap-1 text-muted-gray">
                <Coins className="size-3.5 shrink-0" />
                <span className="comet-body-xs">
                  {usage.totalTokens} tokens
                </span>
              </span>
            )}
          </div>
          <div className="comet-body-s">{renderContent()}</div>
        </div>
      ) : (
        <div className="flex-1 bg-background">
          <PlaygroundNoRunsYet color={promptColor.bg} />
        </div>
      )}
    </div>
  );
};

export default PlaygroundPromptOutput;
