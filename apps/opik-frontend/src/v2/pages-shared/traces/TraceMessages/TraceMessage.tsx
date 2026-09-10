import React, { useMemo } from "react";
import JsonView from "react18-json-view";
import isObject from "lodash/isObject";
import isUndefined from "lodash/isUndefined";
import { ArrowUpRight } from "lucide-react";

import { Trace, USER_FEEDBACK_SCORE } from "@/types/traces";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import LikeFeedback from "@/v2/pages-shared/traces/TraceMessages/LikeFeedback";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { isSpanHiddenByDefault } from "@/v2/pages-shared/traces/spanVisibility";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import { prettifyTraceField } from "@/lib/traces";
import { toString } from "@/lib/utils";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";

const INTERNAL_TRACE_TOOLTIP =
  "Internal call (e.g. title generation), hidden by default";

type TraceMessageProps = {
  trace: Trace;
  handleOpenTrace: (id: string, filterToolCalls: boolean) => void;
};

const TraceMessage: React.FC<TraceMessageProps> = ({
  trace,
  handleOpenTrace,
}) => {
  const jsonViewTheme = useJsonViewTheme();
  // Same flag that hides the trace by default. Once revealed, it is badged so it is not
  // read as a conversation turn.
  const isInternal = isSpanHiddenByDefault(trace);

  const userFeedback = useMemo(() => {
    return (trace.feedback_scores ?? []).find(
      (f) => f.name === USER_FEEDBACK_NAME,
    )?.value as USER_FEEDBACK_SCORE;
  }, [trace.feedback_scores]);

  const input = useMemo(() => {
    const message = prettifyTraceField(trace, "input").message;

    if (isObject(message)) {
      return (
        <JsonView
          src={message}
          {...jsonViewTheme}
          className="comet-code"
          collapseStringsAfterLength={10000}
          enableClipboard={false}
        />
      );
    } else if (isUndefined(message)) {
      return <span>-</span>;
    } else {
      return <MarkdownPreview>{toString(message)}</MarkdownPreview>;
    }
  }, [trace, jsonViewTheme]);

  const output = useMemo(() => {
    const message = prettifyTraceField(trace, "output").message;

    if (isObject(message)) {
      return (
        <JsonView
          src={message}
          className="comet-code"
          {...jsonViewTheme}
          collapseStringsAfterLength={10000}
          enableClipboard={false}
        />
      );
    } else if (isUndefined(message)) {
      return "-";
    } else {
      return <MarkdownPreview>{toString(message)}</MarkdownPreview>;
    }
  }, [trace, jsonViewTheme]);

  return (
    <div className="flex flex-col gap-2" data-trace-message-id={trace.id}>
      <div className="flex justify-end pl-16">
        <div className="rounded-t-xl rounded-bl-xl bg-[var(--message-input-background)] px-4 py-2">
          {input}
        </div>
      </div>
      <div className="flex flex-col gap-1 pr-16">
        <div className="pt-3">{output}</div>
        <div className="flex items-center gap-0.5 p-0.5">
          <LikeFeedback state={userFeedback} traceId={trace.id} />
          <Separator orientation="vertical" className="mx-1 h-3" />
          <Button
            variant="ghost"
            size="2xs"
            className="text-muted-slate"
            onClick={() => handleOpenTrace(trace.id, false)}
          >
            Trace
            <ArrowUpRight className="ml-1 size-3" />
          </Button>
          {trace.has_tool_spans && (
            <Button
              variant="ghost"
              size="2xs"
              className="text-muted-slate"
              onClick={() => handleOpenTrace(trace.id, true)}
            >
              Tool calls
              <ArrowUpRight className="ml-1 size-3" />
            </Button>
          )}
          {isInternal && (
            <TooltipWrapper content={INTERNAL_TRACE_TOOLTIP}>
              <Tag
                variant="gray"
                size="sm"
                className="ml-1"
                role="note"
                tabIndex={0}
                aria-label={INTERNAL_TRACE_TOOLTIP}
              >
                Internal
              </Tag>
            </TooltipWrapper>
          )}
        </div>
      </div>
    </div>
  );
};

export default TraceMessage;
