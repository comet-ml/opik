import React from "react";
import { cn } from "@/lib/utils";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";
import AnnotatorViewOllieAction from "./AnnotatorViewOllieAction";

type AnnotatorViewBannerProps = {
  editor: AnnotatorViewEditor;
};

const BANNER_TEXT = {
  credits: "You can customize this view with Ollie once credits are added.",
  "no-data": "Log traces to this project to customize this view with Ollie.",
  drafting: "Ollie is drafting a view…",
  ready:
    "Describe what to change in the Ollie chat. Each version shows up here.",
};

const AnnotatorViewBanner: React.FC<AnnotatorViewBannerProps> = ({
  editor,
}) => {
  if (editor.ollieState === "none") return null;
  const isOff = editor.ollieState === "credits";
  const isDrafting = editor.ollieState === "drafting";

  return (
    <div
      className={cn(
        "flex min-h-11 items-center justify-between gap-2 rounded-md border px-3 py-1.5",
        isOff
          ? "border-border bg-soft-background text-muted-slate"
          : "border-primary-100 bg-primary-50",
      )}
    >
      <span className="comet-body-xs flex items-center gap-2">
        <span
          className={cn(
            "size-1.5 shrink-0 rounded-full",
            isOff ? "bg-light-slate" : "bg-primary",
            isDrafting && "animate-pulse",
          )}
        />
        {BANNER_TEXT[editor.ollieState]}
      </span>
      {!isDrafting && (
        <AnnotatorViewOllieAction
          editor={editor}
          onStart={() => editor.startOllie()}
        />
      )}
    </div>
  );
};

export default AnnotatorViewBanner;
