import React, { useCallback, useEffect, useRef, useState } from "react";
import { HeaderContext } from "@tanstack/react-table";
import { Check, Pencil, X } from "lucide-react";
import HeaderWrapper from "@/shared/DataTableHeaders/HeaderWrapper";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { PLAYGROUND_PROMPT_COLORS } from "@/constants/llm";
import {
  useFirstOutputUsageByPromptId,
  useDatasetType,
  usePromptById,
  useSetPromptExperimentLabel,
} from "@/store/PlaygroundStore";
import { DATASET_TYPE } from "@/types/datasets";
import usePromptModelDisplay from "@/v2/pages/PlaygroundPage/usePromptModelDisplay";
import usePromptResultStatus, {
  PromptResultStatus,
} from "@/v2/pages/PlaygroundPage/usePromptResultStatus";

interface EditableHeaderLabelProps {
  header: string | undefined;
  promptId: string;
}

const EditableHeaderLabel: React.FC<EditableHeaderLabelProps> = ({
  header,
  promptId,
}) => {
  const prompt = usePromptById(promptId);
  const setPromptExperimentLabel = useSetPromptExperimentLabel();
  const label = prompt?.experimentLabel ?? "";

  const [isEditing, setIsEditing] = useState(false);
  const [draft, setDraft] = useState(label);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isEditing) inputRef.current?.select();
  }, [isEditing]);

  const commit = useCallback(() => {
    setPromptExperimentLabel(promptId, draft.trim());
    setIsEditing(false);
  }, [draft, promptId, setPromptExperimentLabel]);

  const cancel = useCallback(() => {
    setDraft(label);
    setIsEditing(false);
  }, [label]);

  if (isEditing) {
    return (
      <div className="flex shrink-0 items-center gap-0.5">
        <input
          ref={inputRef}
          autoFocus
          value={draft}
          placeholder={header}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            e.stopPropagation();
            if (e.key === "Enter") commit();
            if (e.key === "Escape") cancel();
          }}
          className="h-6 w-32 rounded-md border border-primary bg-background px-2 text-sm outline-none"
        />
        <Button variant="minimal" size="icon-xs" onMouseDown={commit}>
          <Check className="text-special-button" />
        </Button>
        <Button variant="minimal" size="icon-xs" onMouseDown={cancel}>
          <X className="text-destructive" />
        </Button>
      </div>
    );
  }

  return (
    <TooltipWrapper content="Rename experiment">
      <div
        className="group/label flex shrink-0 cursor-pointer items-center gap-1"
        onClick={() => {
          setDraft(label);
          setIsEditing(true);
        }}
      >
        <span className="shrink-0">{label || header}</span>
        <Pencil className="size-3 shrink-0 opacity-0 transition-opacity group-hover/label:opacity-100" />
      </div>
    </TooltipWrapper>
  );
};

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
        <EditableHeaderLabel header={header} promptId={promptId} />
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

interface DatasetColumnHeaderProps {
  header: string | undefined;
  promptId: string;
  promptIndex: number;
}

const DatasetColumnHeader: React.FC<DatasetColumnHeaderProps> = ({
  header,
  promptId,
  promptIndex,
}) => {
  const promptColor =
    PLAYGROUND_PROMPT_COLORS[promptIndex % PLAYGROUND_PROMPT_COLORS.length];

  return (
    <ColumnHeaderLayout
      header={header}
      dotColor={promptColor.bg}
      promptId={promptId}
    />
  );
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
  } else {
    return (
      <DatasetColumnHeader
        header={header}
        promptId={promptId ?? ""}
        promptIndex={promptIndex ?? 0}
      />
    );
  }
};

export default PlaygroundOutputColumnHeader;
