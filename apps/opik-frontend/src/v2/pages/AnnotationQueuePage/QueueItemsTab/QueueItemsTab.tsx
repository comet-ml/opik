import React from "react";

import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import Loader from "@/shared/Loader/Loader";
import TraceQueueItemsTab from "@/v2/pages/AnnotationQueuePage/QueueItemsTab/TraceQueueItemsTab";
import ThreadQueueItemsTab from "@/v2/pages/AnnotationQueuePage/QueueItemsTab/ThreadQueueItemsTab";

interface QueueItemsTabProps {
  annotationQueue?: AnnotationQueue;
}

const QueueItemsTab: React.FunctionComponent<QueueItemsTabProps> = ({
  annotationQueue,
}) => {
  if (!annotationQueue) {
    return <Loader />;
  }

  return annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE ? (
    <TraceQueueItemsTab annotationQueue={annotationQueue} />
  ) : (
    <ThreadQueueItemsTab annotationQueue={annotationQueue} />
  );
};

export default QueueItemsTab;
