import React from "react";
import { Tag } from "@/components/ui/tag";
import { AnnotationQueue } from "@/types/annotation-queues";
import AnnotationQueueProgress from "@/components/pages-shared/annotation-queues/AnnotationQueueProgress";

interface AnnotationQueueProgressTagProps {
  annotationQueue: AnnotationQueue;
}

const AnnotationQueueProgressTag: React.FunctionComponent<
  AnnotationQueueProgressTagProps
> = ({ annotationQueue }) => {
  return (
    <AnnotationQueueProgress annotationQueue={annotationQueue}>
      {({ averageProgress, progressPercentage, itemsCount }) => (
        <Tag variant="gray" size="md" className="cursor-pointer">
          Progress: {averageProgress}/{itemsCount} ({progressPercentage}%)
        </Tag>
      )}
    </AnnotationQueueProgress>
  );
};

export default AnnotationQueueProgressTag;
