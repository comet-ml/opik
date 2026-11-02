import React from "react";
import { ArrowLeft, Undo2 } from "lucide-react";
import Loader from "@/shared/Loader/Loader";
import SandboxedViewPreview from "@/shared/SandboxedView/SandboxedViewPreview";
import { ANNOTATOR_VIEW_PACKS } from "./annotatorViewData";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";
import AnnotatorViewBanner from "./AnnotatorViewBanner";

type AnnotatorViewPreviewPageProps = {
  editor: AnnotatorViewEditor;
  onBack: () => void;
};

const AnnotatorViewPreviewPage: React.FC<AnnotatorViewPreviewPageProps> = ({
  editor,
  onBack,
}) => {
  const unit = editor.scope;

  const renderPreview = () => {
    if (editor.isSamplesPending && !editor.samples.length) return <Loader />;
    if (!editor.samples.length) {
      return (
        <div className="flex flex-col items-center gap-1 rounded-md border border-border px-4 py-10 text-center">
          <span className="comet-body-s-accented">No {unit}s to preview</span>
          <span className="comet-body-xs text-muted-slate">
            {unit === "thread"
              ? "Log traces with a thread ID to this project to see how annotators will see them."
              : "Log traces to this project to see how annotators will see them."}
          </span>
        </div>
      );
    }
    return (
      <div className="relative">
        <SandboxedViewPreview
          samples={editor.samples}
          sampleIndex={editor.sampleIndex}
          onSampleIndexChange={editor.setSampleIndex}
          html={editor.draft.html}
          kitVersion={editor.draft.kitVersion}
          packs={ANNOTATOR_VIEW_PACKS}
          title="Annotator view"
          failureDescription={`The custom view failed on this ${unit}, so annotators will see the default view.`}
          onFixError={
            editor.ollieState === "ready" || editor.ollieState === "drafting"
              ? (error) => editor.startOllie(error)
              : undefined
          }
        />
        {editor.flashKey > 0 && (
          <div
            key={editor.flashKey}
            className="pointer-events-none absolute inset-0 animate-view-flash rounded-md"
          />
        )}
      </div>
    );
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 flex-col gap-1.5 border-b border-border bg-background px-4 py-3">
        <div className="flex h-6 items-center justify-between gap-2">
          <button
            type="button"
            className="comet-title-xs flex items-center gap-1 pr-0.5 leading-[18px] hover:text-primary"
            aria-label="Back to queue settings"
            onClick={onBack}
          >
            <span className="flex size-6 items-center justify-center">
              <ArrowLeft className="size-3" />
            </span>
            Previewing annotator view
          </button>
          {editor.hasView && (
            <button
              type="button"
              className="comet-body-xs flex h-6 items-center gap-0.5 hover:text-primary"
              onClick={editor.reset}
            >
              <Undo2 className="size-3" />
              Revert to default
            </button>
          )}
        </div>
        <AnnotatorViewBanner editor={editor} />
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
        {renderPreview()}
      </div>
    </div>
  );
};

export default AnnotatorViewPreviewPage;
