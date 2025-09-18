import React from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import Loader from "@/components/shared/Loader/Loader";
import InstructionsSection from "@/components/pages/AnnotationQueuePage/ConfigurationTab/InstructionsSection";
import ScoresSection from "@/components/pages/AnnotationQueuePage/ConfigurationTab/ScoresSection";
import ReviewersSection from "@/components/pages/AnnotationQueuePage/ConfigurationTab/ReviewersSection";
import CopySMELinkButton from "@/components/pages/AnnotationQueuePage/shared/CopySMELinkButton";
import EditAnnotationQueueButton from "@/components/pages/AnnotationQueuePage/shared/EditAnnotationQueueButton";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";

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
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-end gap-x-8 gap-y-2 py-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <CopySMELinkButton annotationQueue={annotationQueue} />
          <EditAnnotationQueueButton annotationQueue={annotationQueue} />
        </div>
      </PageBodyStickyContainer>
      <div className="px-6">
        <InstructionsSection annotationQueue={annotationQueue} />
        <ScoresSection annotationQueue={annotationQueue} />
        <ReviewersSection annotationQueue={annotationQueue} />
      </div>
    </>
  );
};

export default ConfigurationTab;
