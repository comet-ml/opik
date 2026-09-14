import React, { useEffect, useState } from "react";
import ErrorTriangle from "@/icons/error-triangle.svg?react";
import { BaseTraceDataErrorInfo } from "@/types/traces";
import CodeBlock from "./CodeBlock";

type ErrorCalloutProps = {
  error?: BaseTraceDataErrorInfo;
  search?: string;
  /**
   * Notified whenever the error is on screen AND open. Must be referentially
   * stable: it is an effect dependency, and a new identity on every parent
   * render would re-report the same state.
   */
  onExpandedChange?: (expanded: boolean) => void;
};

const ErrorCallout: React.FunctionComponent<ErrorCalloutProps> = ({
  error,
  search,
  onExpandedChange,
}) => {
  // Mirrors the section's own open state rather than making the shared CodeBlock
  // expose it. The two cannot drift: both start closed and only the toggle moves
  // either of them.
  const [isOpen, setIsOpen] = useState(false);
  const hasError = Boolean(error);

  // Reported as `false` whenever the section is not on screen — no error on this
  // span, or the viewer swapped for a skeleton — so nobody is left holding a
  // stale "expanded" for a section that is gone.
  useEffect(() => {
    onExpandedChange?.(hasError && isOpen);
    return () => onExpandedChange?.(false);
  }, [hasError, isOpen, onExpandedChange]);

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
      onOpenChange={setIsOpen}
      className="mb-4 border-destructive"
    />
  );
};

export default ErrorCallout;
