import React from "react";
import { Info, LayoutTemplate, Maximize2 } from "lucide-react";
import { Button } from "@/ui/button";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";
import AnnotatorViewOllieAction from "./AnnotatorViewOllieAction";
import AnnotatorViewStatusTag from "./AnnotatorViewStatusTag";
import AnnotatorViewThumbnail from "./AnnotatorViewThumbnail";
import {
  getAnnotatorViewDescription,
  getAnnotatorViewNote,
} from "./annotatorViewCopy";

type AnnotatorViewCardProps = {
  editor: AnnotatorViewEditor;
  onPreview: () => void;
  onStartOllie: () => void;
  isVisible: boolean;
};

const AnnotatorViewCard: React.FC<AnnotatorViewCardProps> = ({
  editor,
  onPreview,
  onStartOllie,
  isVisible,
}) => {
  const note = getAnnotatorViewNote(editor);

  return (
    <div className="rounded-md border border-border">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2.5">
        <span className="flex size-5 items-center justify-center rounded-md bg-primary-100 text-primary">
          <LayoutTemplate className="size-3" />
        </span>
        <span className="comet-body-s-accented">Annotator view</span>
        <AnnotatorViewStatusTag editor={editor} />
      </div>
      <div className="flex items-start gap-3.5 p-3">
        {isVisible && <AnnotatorViewThumbnail editor={editor} />}
        <div className="flex min-w-0 flex-1 flex-col gap-2.5 self-stretch">
          <p className="comet-body-s text-muted-slate">
            {getAnnotatorViewDescription(editor)}
          </p>
          {note && (
            <p className="comet-body-xs flex items-start gap-1.5 text-muted-slate">
              <Info className="mt-0.5 size-3 shrink-0" />
              {note}
            </p>
          )}
          <div className="mt-auto flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              size="2xs"
              onClick={onPreview}
            >
              <Maximize2 className="mr-1 size-3" />
              View preview
            </Button>
            <AnnotatorViewOllieAction editor={editor} onStart={onStartOllie} />
          </div>
        </div>
      </div>
    </div>
  );
};

export default AnnotatorViewCard;
