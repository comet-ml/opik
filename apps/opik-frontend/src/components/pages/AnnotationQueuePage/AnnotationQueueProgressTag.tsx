import React from "react";
import { Tag } from "@/components/ui/tag";
import { AnnotationQueue } from "@/types/annotation-queues";
import AnnotationQueueProgress from "@/components/pages-shared/annotation-queues/AnnotationQueueProgress";
import { SquareCheck } from "lucide-react";

interface AnnotationQueueProgressTagProps {
  annotationQueue: AnnotationQueue;
}

const AnnotationQueueProgressTag: React.FunctionComponent<
  AnnotationQueueProgressTagProps
> = ({ annotationQueue }) => {
  return (
    <AnnotationQueueProgress annotationQueue={annotationQueue}>
      {({ averageProgress, progressPercentage, itemsCount }) => (
        <Tag
          variant="transparent"
          size="md"
          className="comet-body-s-accented flex cursor-pointer items-center gap-1 text-muted-slate"
        >
          <SquareCheck className="size-3 shrink-0 text-[var(--color-red)]" />
          {averageProgress}/{itemsCount} ({progressPercentage}%)
        </Tag>
      )}
    </AnnotationQueueProgress>
  );
};

export default AnnotationQueueProgressTag;
