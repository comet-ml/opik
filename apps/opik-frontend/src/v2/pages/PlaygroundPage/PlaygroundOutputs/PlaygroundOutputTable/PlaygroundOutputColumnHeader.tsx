import { HeaderContext } from "@tanstack/react-table";
import HeaderWrapper from "@/shared/DataTableHeaders/HeaderWrapper";
import { PLAYGROUND_PROMPT_COLORS } from "@/constants/llm";
import { useFirstOutputUsageByPromptId } from "@/store/PlaygroundStore";
import usePromptModelDisplay from "@/v2/pages/PlaygroundPage/usePromptModelDisplay";

const PlaygroundOutputColumnHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column } = context;
  const { header, custom } = column.columnDef.meta ?? {};
  const { promptId, promptIndex } =
    (custom as {
      promptId?: string;
      promptIndex?: number;
    }) ?? {};
  const colorIndex = promptIndex ?? 0;
  const promptColor =
    PLAYGROUND_PROMPT_COLORS[colorIndex % PLAYGROUND_PROMPT_COLORS.length];

  const usage = useFirstOutputUsageByPromptId(promptId ?? "");
  const { ProviderIcon, modelLabel } = usePromptModelDisplay(
    usage?.provider,
    usage?.model,
  );

  return (
    <HeaderWrapper>
      <div className="flex items-center gap-1.5">
        <span
          className="inline-block size-3 shrink-0 rounded-sm"
          style={{ backgroundColor: promptColor.bg }}
        />
        <span className="shrink-0">{header}</span>
        {modelLabel && ProviderIcon && (
          <span className="flex min-w-0 items-center gap-1 text-muted-gray">
            <ProviderIcon className="size-3.5 shrink-0" />
            <span className="comet-body-xs truncate">{modelLabel}</span>
          </span>
        )}
      </div>
    </HeaderWrapper>
  );
};

export default PlaygroundOutputColumnHeader;
