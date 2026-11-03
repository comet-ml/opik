import React from "react";
import { ArrowRight, ClipboardPen } from "lucide-react";
import { Button } from "@/ui/button";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";
import {
  LogTracesButton,
  OllieDraftingButton,
  OllieOutOfCreditsButton,
  OllieStartButton,
} from "./AnnotatorViewOllieAction";

type AnnotatorViewCardProps = {
  editor: AnnotatorViewEditor;
  onPreview: () => void;
  onStartOllie: () => void;
};

const AnnotatorViewCard: React.FC<AnnotatorViewCardProps> = ({
  editor,
  onPreview,
  onStartOllie,
}) => {
  const hasNoSamples = !editor.isSamplesPending && !editor.samples.length;
  const hasOllie = editor.ollieState !== "none";
  const customizeWith = hasOllie ? "with Ollie or via MCP" : "via MCP";

  const previewButton = (
    <Button type="button" variant="outline" size="2xs" onClick={onPreview}>
      Preview
      <ArrowRight className="ml-1 size-3" />
    </Button>
  );

  const renderActions = () => {
    if (hasNoSamples) return <LogTracesButton />;
    switch (editor.ollieState) {
      case "none":
        return previewButton;
      case "credits":
        return (
          <>
            {previewButton}
            <OllieOutOfCreditsButton />
          </>
        );
      case "drafting":
        return <OllieDraftingButton />;
      default:
        return (
          <OllieStartButton
            label="Preview & edit with Ollie"
            onClick={onStartOllie}
          />
        );
    }
  };

  const renderDescription = () => {
    if (editor.hasView) {
      return editor.draft.summary || "Annotators see a custom view.";
    }
    const defaultView = (
      <>
        Annotators see the{" "}
        <span className="comet-body-xs-accented">
          {editor.scope === "thread"
            ? "thread as a conversation of chat messages"
            : "trace input, output and metadata as code blocks"}
        </span>
        .
      </>
    );
    if (hasNoSamples) {
      return (
        <>
          No {editor.scope}s yet. {defaultView} Log traces to customize this
          view {customizeWith}.
        </>
      );
    }
    return (
      <>
        {defaultView} You can customize this view {customizeWith}.
      </>
    );
  };

  return (
    <div className="flex flex-col gap-1.5 rounded-md border border-border bg-soft-background p-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="flex h-5 items-center justify-center rounded-md bg-[var(--rule-icon-llm-judge)] px-1">
            <ClipboardPen className="size-3 text-black" />
          </span>
          <span className="comet-body-s-accented">Annotator view</span>
        </div>
        <div className="flex items-center gap-2">{renderActions()}</div>
      </div>
      <p className="comet-body-xs">{renderDescription()}</p>
    </div>
  );
};

export default AnnotatorViewCard;
