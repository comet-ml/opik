import React from "react";
import isUndefined from "lodash/isUndefined";
import { CellContext } from "@tanstack/react-table";
import { Link, useParams } from "@tanstack/react-router";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import TextDiff from "@/shared/CodeDiff/TextDiff";
import { toString } from "@/lib/utils";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";

export type CompareFiledValue = string | number | undefined | null;

export type CompareConfig = {
  name: string;
  data: Record<string, CompareFiledValue>;
  base: string;
  different: boolean;
};

export type AgentConfigLinkData = {
  projectId: string;
  blueprintId: string;
};

type CustomMeta = {
  onlyDiff: boolean;
  agentConfigLinkData?: Record<string, AgentConfigLinkData>;
};

const CompareExperimentsConfigCell: React.FC<
  CellContext<CompareConfig, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onlyDiff, agentConfigLinkData } = (custom ?? {}) as CustomMeta;
  const experimentId = context.column?.id;
  const compareConfig = context.row.original;
  const { workspaceName } = useParams({ strict: false }) as {
    workspaceName?: string;
  };

  const data = compareConfig.data[experimentId];
  const baseData = compareConfig.data[compareConfig.base];
  const linkData =
    compareConfig.name === "agent_configuration"
      ? agentConfigLinkData?.[experimentId]
      : undefined;

  const renderContent = () => {
    if (isUndefined(data)) {
      return <span className="px-1.5 py-2.5 text-light-slate">No value</span>;
    }

    if (linkData && workspaceName) {
      return (
        <div className="comet-code size-full max-w-full overflow-hidden whitespace-pre-wrap break-words rounded-md border bg-code-block px-2 py-[11px]">
          <Link
            to="/$workspaceName/projects/$projectId/agent-configuration"
            params={{
              workspaceName,
              projectId: linkData.projectId,
            }}
            search={{ configId: linkData.blueprintId }}
            className="text-foreground underline hover:no-underline"
          >
            {data}
          </Link>
        </div>
      );
    }

    return (
      <div className="comet-code size-full max-w-full overflow-hidden whitespace-pre-wrap break-words rounded-md border bg-code-block px-2 py-[11px]">
        {showDiffView ? (
          <TextDiff content1={toString(baseData)} content2={toString(data)} />
        ) : (
          <LinkifyText>{toString(data)}</LinkifyText>
        )}
      </div>
    );
  };

  const showDiffView =
    onlyDiff &&
    Object.values(compareConfig.data).length >= 2 &&
    experimentId !== compareConfig.base;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={{
        ...context.table.options.meta,
        rowHeight: ROW_HEIGHT.small,
        rowHeightStyle: { minHeight: "52px" },
      }}
      className="p-1.5"
    >
      {renderContent()}
    </CellWrapper>
  );
};

export default CompareExperimentsConfigCell;
