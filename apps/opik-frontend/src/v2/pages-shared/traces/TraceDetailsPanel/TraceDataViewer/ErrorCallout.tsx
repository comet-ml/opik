import React from "react";
import ErrorTriangle from "@/icons/error-triangle.svg?react";
import { BaseTraceDataErrorInfo } from "@/types/traces";
import CodeBlock from "./CodeBlock";

type ErrorCalloutProps = {
  error?: BaseTraceDataErrorInfo;
  search?: string;
};

const ErrorCallout: React.FunctionComponent<ErrorCalloutProps> = ({
  error,
  search,
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
      className="mb-4 border-destructive"
    />
  );
};

export default ErrorCallout;
