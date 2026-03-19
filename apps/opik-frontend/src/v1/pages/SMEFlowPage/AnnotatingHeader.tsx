import React from "react";
import { useSMEFlow } from "./SMEFlowContext";

interface AnnotatingHeaderProps {
  content?: React.ReactNode;
}

const AnnotatingHeader: React.FunctionComponent<AnnotatingHeaderProps> = ({
  content,
}) => {
  const { annotationQueue, totalCount, processedCount } = useSMEFlow();

  return (
    <>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          {annotationQueue?.name}
        </h1>
        {content}
      </div>
      <div className="mb-2 flex h-7 items-center justify-between">
        <div className="comet-body-s-accented text-foreground">Progress</div>
        <span className="comet-body-s text-light-slate">
          {processedCount}/{totalCount} completed
        </span>
      </div>
      <div className="flex flex-1 items-center space-x-4">
        <div className="h-2 flex-1 rounded-full bg-secondary">
          <div
            className="h-2 rounded-full bg-primary transition-all duration-300"
            style={{ width: `${(processedCount / totalCount) * 100}%` }}
          />
        </div>
      </div>
    </>
  );
};

export default AnnotatingHeader;
