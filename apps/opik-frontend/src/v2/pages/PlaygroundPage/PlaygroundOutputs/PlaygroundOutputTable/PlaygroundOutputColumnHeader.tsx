import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import HeaderWrapper from "@/shared/DataTableHeaders/HeaderWrapper";
import { PLAYGROUND_PROMPT_COLORS } from "@/constants/llm";
import {
  useFirstOutputUsageByPromptId,
  useDatasetType,
} from "@/store/PlaygroundStore";
import { DATASET_TYPE } from "@/types/datasets";
import usePromptModelDisplay from "@/v2/pages/PlaygroundPage/usePromptModelDisplay";
import usePromptResultStatus, {
  PromptResultStatus,
} from "@/v2/pages/PlaygroundPage/usePromptResultStatus";

interface ColumnHeaderLayoutProps {
  header: string | undefined;
  dotColor: string;
  children?: React.ReactNode;
  promptId: string;
}

const ColumnHeaderLayout: React.FC<ColumnHeaderLayoutProps> = ({
  header,
  dotColor,
  children,
  promptId,
}) => {
  const usage = useFirstOutputUsageByPromptId(promptId);
  const { ProviderIcon, modelLabel } = usePromptModelDisplay(
    usage?.provider,
    usage?.model,
  );

  return (
    <HeaderWrapper>
      <div className="flex items-center gap-1.5">
        <span
          className="inline-block size-3 shrink-0 rounded-sm"
          style={{ backgroundColor: dotColor }}
        />
        <span className="shrink-0">{header}</span>
        {children}
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

interface TestSuiteColumnHeaderProps {
  header: string | undefined;
  promptId: string;
}

const DOT_COLOR: Record<PromptResultStatus, string> = {
  default: "var(--click-blue)",
  winner: "var(--chart-green)",
  loser: "var(--chart-red)",
};

const PASS_RATE_TEXT_COLOR: Record<PromptResultStatus, string> = {
  default: "",
  winner: "var(--tag-green-text)",
  loser: "var(--tag-red-text)",
};

const TestSuiteColumnHeader: React.FC<TestSuiteColumnHeaderProps> = ({
  header,
  promptId,
}) => {
  const { status, promptResult } = usePromptResultStatus(promptId);

  return (
    <ColumnHeaderLayout
      header={header}
      dotColor={DOT_COLOR[status]}
      promptId={promptId}
    >
      {status !== "default" && promptResult?.passRate != null && (
        <span
          className="shrink-0 text-xs"
          style={{ color: PASS_RATE_TEXT_COLOR[status] }}
        >
          {Math.round(promptResult.passRate * 100)}% pass rate
        </span>
      )}
    </ColumnHeaderLayout>
  );
};

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

  const datasetType = useDatasetType();
  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;

  if (isTestSuite) {
    return <TestSuiteColumnHeader header={header} promptId={promptId ?? ""} />;
  }

  const colorIndex = promptIndex ?? 0;
  const promptColor =
    PLAYGROUND_PROMPT_COLORS[colorIndex % PLAYGROUND_PROMPT_COLORS.length];

  return (
    <ColumnHeaderLayout
      header={header}
      dotColor={promptColor.bg}
      promptId={promptId ?? ""}
    />
  );
};

export default PlaygroundOutputColumnHeader;
