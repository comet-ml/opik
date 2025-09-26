import React from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

interface InstructionsContentProps {
  annotationQueue: AnnotationQueue;
}

const InstructionsContent: React.FunctionComponent<
  InstructionsContentProps
> = ({ annotationQueue }) => {
  if (!annotationQueue.instructions) {
    return null;
  }

  return (
    <div className="rounded-lg border bg-background">
      <div className="p-6">
        <MarkdownPreview>{annotationQueue.instructions}</MarkdownPreview>
      </div>
    </div>
  );
};

export default InstructionsContent;
