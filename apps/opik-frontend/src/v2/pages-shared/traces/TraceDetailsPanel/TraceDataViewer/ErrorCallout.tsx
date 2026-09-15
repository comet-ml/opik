import React from "react";
import ErrorTriangle from "@/icons/error-triangle.svg?react";
import { BaseTraceDataErrorInfo } from "@/types/traces";
import CodeBlock from "./CodeBlock";

type ErrorCalloutProps = {
  error?: BaseTraceDataErrorInfo;
  search?: string;
  /**
   * Controlled open state. Passed straight through — the section deliberately
   * keeps no copy, so nothing can drift from whoever owns it.
   */
  isExpanded?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
};

const ErrorCallout: React.FunctionComponent<ErrorCalloutProps> = ({
  error,
  search,
  isExpanded,
  onExpandedChange,
}) => {
  if (!error) return null;

  return (
    <CodeBlock
      title={
        <span className="flex items-center gap-1">
          <span className="flex size-4 shrink-0 items-center justify-center text-destructive">
            <ErrorTriangle width={12} height={12} />
          </span>
          Error
        </span>
      }
      data={error}
      preserveKey="syntax-highlighter-trace-sidebar-error"
      withSearch
      search={search}
      defaultOpen={false}
      open={isExpanded}
      onOpenChange={onExpandedChange}
      className="mb-4 border-destructive"
    />
  );
};

export default ErrorCallout;
