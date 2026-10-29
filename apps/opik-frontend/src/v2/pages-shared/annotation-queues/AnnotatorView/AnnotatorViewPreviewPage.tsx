import React from "react";
import { ArrowLeft, Info, Trash } from "lucide-react";
import { Button } from "@/ui/button";
import Loader from "@/shared/Loader/Loader";
import SandboxedViewPreview from "@/shared/SandboxedView/SandboxedViewPreview";
import { ANNOTATOR_VIEW_PACKS } from "./annotatorViewData";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";
import AnnotatorViewStatusTag from "./AnnotatorViewStatusTag";
import AnnotatorViewBanner from "./AnnotatorViewBanner";
import {
  getAnnotatorViewDescription,
  getAnnotatorViewNote,
} from "./annotatorViewCopy";

type AnnotatorViewPreviewPageProps = {
  editor: AnnotatorViewEditor;
  onBack: () => void;
};

const AnnotatorViewPreviewPage: React.FC<AnnotatorViewPreviewPageProps> = ({
  editor,
  onBack,
}) => {
  const note = getAnnotatorViewNote(editor);
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
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2.5">
        <Button
          variant="outline"
          size="icon-xs"
          aria-label="Back to queue settings"
          onClick={onBack}
        >
          <ArrowLeft />
        </Button>
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2">
            <span className="comet-body-s-accented">
              Previewing annotator view
            </span>
            <AnnotatorViewStatusTag editor={editor} />
          </div>
          <span
            className="comet-body-xs truncate text-muted-slate"
            title={getAnnotatorViewDescription(editor)}
          >
            {getAnnotatorViewDescription(editor)} · read-only
          </span>
        </div>
        {editor.hasView && (
          <Button variant="ghost" size="2xs" onClick={editor.reset}>
            <Trash className="mr-1 size-3" />
            Reset
          </Button>
        )}
      </div>
      <AnnotatorViewBanner editor={editor} />
      {renderPreview()}
      {note && editor.samples.length > 0 && (
        <p className="comet-body-xs flex items-start gap-1.5 text-muted-slate">
          <Info className="mt-0.5 size-3 shrink-0" />
          {note}
        </p>
      )}
    </div>
  );
};

export default AnnotatorViewPreviewPage;
