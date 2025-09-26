import React from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import InstructionsContent from "@/components/pages-shared/annotation-queues/InstructionsContent";

interface InstructionsSectionProps {
  annotationQueue: AnnotationQueue;
}

const InstructionsSection: React.FunctionComponent<
  InstructionsSectionProps
> = ({ annotationQueue }) => {
  return (
    <div>
      <h2 className="comet-title-s truncate break-words pb-3 pt-2">
        Instructions
      </h2>
      <InstructionsContent annotationQueue={annotationQueue} />
    </div>
  );
};

export default InstructionsSection;
