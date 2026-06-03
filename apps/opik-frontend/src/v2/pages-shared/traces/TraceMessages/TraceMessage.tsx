import React, { useMemo } from "react";
import JsonView from "react18-json-view";
import isObject from "lodash/isObject";
import isUndefined from "lodash/isUndefined";

import { Trace, USER_FEEDBACK_SCORE } from "@/types/traces";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import LikeFeedback from "@/v2/pages-shared/traces/TraceMessages/LikeFeedback";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import { prettifyMessage } from "@/lib/traces";
import { toString } from "@/lib/utils";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";
import { ArrowUpRight } from "lucide-react";

type TraceMessageProps = {
  trace: Trace;
  handleOpenTrace: (id: string, filterToolCalls: boolean) => void;
};

const TraceMessage: React.FC<TraceMessageProps> = ({
  trace,
  handleOpenTrace,
}) => {
  const jsonViewTheme = useJsonViewTheme();

  const userFeedback = useMemo(() => {
    return (trace.feedback_scores ?? []).find(
      (f) => f.name === USER_FEEDBACK_NAME,
    )?.value as USER_FEEDBACK_SCORE;
  }, [trace.feedback_scores]);

  const input = useMemo(() => {
    const message = prettifyMessage(trace.input).message;

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
  }, [trace.input, jsonViewTheme]);

  const output = useMemo(() => {
    const message = prettifyMessage(trace.output, { type: "output" }).message;

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
  }, [trace.output, jsonViewTheme]);

  return (
    <div className="flex flex-col gap-2" data-trace-message-id={trace.id}>
      <div key={`${trace.id}_input`} className="flex justify-end pl-16">
        <div className="rounded-t-xl rounded-bl-xl bg-[var(--message-input-background)] px-4 py-2">
          {input}
        </div>
      </div>
      <div key={`${trace.id}_output`} className="flex flex-col gap-1 pr-16">
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
        </div>
      </div>
    </div>
  );
};

export default TraceMessage;
