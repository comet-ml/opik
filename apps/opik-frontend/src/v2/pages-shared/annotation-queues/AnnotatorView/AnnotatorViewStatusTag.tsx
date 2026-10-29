import React from "react";
import { Tag } from "@/ui/tag";
import { AnnotatorViewEditor } from "./useAnnotatorViewEditor";

type AnnotatorViewStatusTagProps = {
  editor: AnnotatorViewEditor;
};

const AnnotatorViewStatusTag: React.FC<AnnotatorViewStatusTagProps> = ({
  editor,
}) => (
  <span className="flex items-center gap-1.5">
    <Tag variant={editor.hasView ? "orange" : "gray"} size="default">
      {editor.hasView ? "Custom view" : "Default view"}
    </Tag>
    {editor.isUnsaved && (
      <span className="comet-body-xs text-muted-slate">· unsaved</span>
    )}
  </span>
);

export default AnnotatorViewStatusTag;
