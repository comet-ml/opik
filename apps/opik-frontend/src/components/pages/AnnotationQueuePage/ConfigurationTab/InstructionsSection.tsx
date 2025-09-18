import React from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

interface InstructionsSectionProps {
  annotationQueue: AnnotationQueue;
}

const InstructionsSection: React.FunctionComponent<
  InstructionsSectionProps
> = ({ annotationQueue }) => {
  if (!annotationQueue.instructions) {
    return null;
  }

  return (
    <div>
      <h2 className="comet-title-s truncate break-words bg-soft-background pb-3 pt-2">
        Instructions
      </h2>
      <div className="rounded-lg border">
        <div className="p-6">
          <MarkdownPreview>{annotationQueue.instructions}</MarkdownPreview>
        </div>
      </div>
    </div>
  );
};

export default InstructionsSection;
