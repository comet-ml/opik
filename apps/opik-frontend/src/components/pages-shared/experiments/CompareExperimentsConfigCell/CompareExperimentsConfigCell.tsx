import React from "react";
import isUndefined from "lodash/isUndefined";
import { CellContext } from "@tanstack/react-table";
import Linkify from "linkify-react";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import { toString } from "@/lib/utils";
import { prettifyMessage } from "@/lib/prettifyMessage";
import { isConversationData } from "@/lib/conversationUtils";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

export type CompareFiledValue = string | number | undefined | null;

export type CompareConfig = {
  name: string;
  data: Record<string, CompareFiledValue>;
  base: string;
  different: boolean;
};

type CustomMeta = {
  onlyDiff: boolean;
};

const CompareExperimentsConfigCell: React.FC<
  CellContext<CompareConfig, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onlyDiff } = (custom ?? {}) as CustomMeta;
  const experimentId = context.column?.id;
  const compareConfig = context.row.original;

  const data = compareConfig.data[experimentId];
  const baseData = compareConfig.data[compareConfig.base];

  const renderContent = () => {
    if (isUndefined(data)) {
      return <span className="px-1.5 py-2.5 text-light-slate">No value</span>;
    }

    // Check if this is conversation data that should be prettified
    if (isConversationData(data) && !showDiffView) {
      // Use pretty formatting for conversation data
      const prettified = prettifyMessage(data as string | object | undefined);
      if (prettified.prettified) {
        return (
          <div className="size-full max-w-full overflow-hidden whitespace-pre-wrap break-words rounded-md border bg-code-block px-2 py-[11px]">
            <MarkdownPreview className="prose prose-sm max-w-none">
              {typeof prettified.message === "string"
                ? prettified.message
                : JSON.stringify(prettified.message)}
            </MarkdownPreview>
          </div>
        );
      }
    }

    return (
      <div className="comet-code size-full max-w-full overflow-hidden whitespace-pre-wrap break-words rounded-md border bg-code-block px-2 py-[11px]">
        {showDiffView ? (
          <TextDiff content1={toString(baseData)} content2={toString(data)} />
        ) : (
          <Linkify
            options={{
              defaultProtocol: "https",
              target: "_blank",
              rel: "noopener noreferrer",
              className:
                "break-all text-blue-600 underline hover:text-blue-800",
              ignoreTags: ["script", "style"],
              validate: {
                url: (value) => /^https?:\/\//.test(value),
              },
            }}
          >
            {toString(data)}
          </Linkify>
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
