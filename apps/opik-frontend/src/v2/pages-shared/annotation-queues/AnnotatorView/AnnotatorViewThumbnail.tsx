import React from "react";
import Loader from "@/shared/Loader/Loader";
import SandboxedView from "@/shared/SandboxedView/SandboxedView";
import { ANNOTATOR_VIEW_PACKS } from "./annotatorViewData";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";

type AnnotatorViewThumbnailProps = {
  editor: AnnotatorViewEditor;
};

const AnnotatorViewThumbnail: React.FC<AnnotatorViewThumbnailProps> = ({
  editor,
}) => {
  const sample = editor.samples[editor.sampleIndex];

  if (!sample) {
    return (
      <div className="comet-body-xs flex h-[124px] w-[152px] shrink-0 items-center justify-center rounded-md border border-dashed border-border text-light-slate">
        {editor.isSamplesPending ? (
          <Loader />
        ) : editor.scope === "thread" ? (
          "No threads"
        ) : (
          "No traces"
        )}
      </div>
    );
  }

  return (
    <div
      className="pointer-events-none h-[124px] w-[152px] shrink-0 overflow-hidden rounded-md border border-border bg-soft-background"
      aria-hidden
    >
      <div className="w-[520px] origin-top-left scale-[0.292] p-3">
        {sample.data === undefined ? null : editor.hasView ? (
          <SandboxedView
            html={editor.draft.html}
            data={sample.data}
            kitVersion={editor.draft.kitVersion}
            packs={ANNOTATOR_VIEW_PACKS}
            title="Annotator view thumbnail"
          />
        ) : (
          sample.fallback
        )}
      </div>
    </div>
  );
};

export default AnnotatorViewThumbnail;
