import React from "react";
import isString from "lodash/isString";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { prettifyMessage } from "@/lib/traces";
import { Trace } from "@/types/traces";
import AnnotatorFieldValue from "./AnnotatorFieldValue";
import AnnotatorFieldsView from "./AnnotatorFieldsView";
import { extractMessageText, resolveMessage } from "./annotatorViewConfig";
import { AnnotatorViewDraft } from "./useAnnotatorViewDraft";

const ThreadMessagePreview: React.FC<{
  trace: Trace;
  value: AnnotatorViewDraft;
}> = ({ trace, value }) => {
  const renderMessage = (
    data: unknown,
    path: string | undefined,
    type: "input" | "output",
  ) => {
    if (path) {
      return (
        <AnnotatorFieldValue
          value={extractMessageText(resolveMessage(data, path))}
        />
      );
    }
    const message = prettifyMessage(data as object, { type }).message;
    return isString(message) ? (
      <MarkdownPreview>{message}</MarkdownPreview>
    ) : (
      <AnnotatorFieldValue value={message} />
    );
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex justify-end pl-16">
        <div className="rounded-t-xl rounded-bl-xl bg-[var(--message-input-background)] px-4 py-2">
          {renderMessage(trace.input, value.input, "input")}
        </div>
      </div>
      <div className="pr-16">
        {renderMessage(trace.output, value.output, "output")}
      </div>
    </div>
  );
};

const TraceFieldsPreview: React.FC<{
  trace: Trace;
  value: AnnotatorViewDraft;
}> = ({ trace, value }) => {
  const { media, transformedInput, transformedOutput } = useUnifiedMedia(trace);

  return (
    <MediaProvider media={media}>
      <AnnotatorFieldsView
        fields={value.fields}
        source={{
          input: transformedInput,
          output: transformedOutput,
          metadata: trace.metadata,
        }}
        media={media}
        feedbackScores={trace.feedback_scores}
      />
    </MediaProvider>
  );
};

type AnnotatorViewPreviewProps = {
  samples: Trace[];
  sample?: Trace;
  sampleIndex: number;
  onSampleIndexChange: (index: number) => void;
  isThreadScope: boolean;
  value: AnnotatorViewDraft;
};

const AnnotatorViewPreview: React.FC<AnnotatorViewPreviewProps> = ({
  samples,
  sample,
  sampleIndex,
  onSampleIndexChange,
  isThreadScope,
  value,
}) => (
  <div className="flex flex-col gap-2">
    <div className="flex flex-col gap-0.5">
      <Label className="px-0.5">Preview</Label>
      <p className="comet-body-xs px-0.5 text-light-slate">
        {samples.length
          ? `How an annotator will see each trace. Values are read from a sample of ${samples.length} traces.`
          : "Log a trace to this project to see a preview."}
      </p>
    </div>
    {sample && (
      <div className="overflow-hidden rounded-md border border-border">
        <div className="flex h-9 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
          <span className="comet-body-xs-accented truncate">
            Trace: {sample.name}
          </span>
          {samples.length > 1 && (
            <div className="flex shrink-0 items-center gap-1">
              <Button
                variant="minimal"
                size="icon-2xs"
                aria-label="Previous sample"
                disabled={sampleIndex === 0}
                onClick={() => onSampleIndexChange(sampleIndex - 1)}
              >
                <ChevronLeft />
              </Button>
              <span className="comet-body-xs text-muted-slate">
                {sampleIndex + 1} of {samples.length}
              </span>
              <Button
                variant="minimal"
                size="icon-2xs"
                aria-label="Next sample"
                disabled={sampleIndex >= samples.length - 1}
                onClick={() => onSampleIndexChange(sampleIndex + 1)}
              >
                <ChevronRight />
              </Button>
            </div>
          )}
        </div>
        <div className="p-3">
          {isThreadScope ? (
            <ThreadMessagePreview trace={sample} value={value} />
          ) : (
            <TraceFieldsPreview key={sample.id} trace={sample} value={value} />
          )}
        </div>
      </div>
    )}
  </div>
);

export default AnnotatorViewPreview;
