import React from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import Loader from "@/shared/Loader/Loader";
import InstructionsSection from "@/v1/pages/AnnotationQueuePage/ConfigurationTab/InstructionsSection";
import ScoresSection from "@/v1/pages/AnnotationQueuePage/ConfigurationTab/ScoresSection";
import ReviewersSection from "@/v1/pages/AnnotationQueuePage/ConfigurationTab/ReviewersSection";

interface ConfigurationTabProps {
  annotationQueue?: AnnotationQueue;
}

const ConfigurationTab: React.FunctionComponent<ConfigurationTabProps> = ({
  annotationQueue,
}) => {
  if (!annotationQueue) {
    return <Loader message="Loading queue information" />;
  }

  return (
    <div className="px-6">
      <InstructionsSection annotationQueue={annotationQueue} />
      <ScoresSection annotationQueue={annotationQueue} />
      <ReviewersSection annotationQueue={annotationQueue} />
    </div>
  );
};

export default ConfigurationTab;
