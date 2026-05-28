import React from "react";
import { useSMEFlow } from "./SMEFlowContext";

interface AnnotatingHeaderProps {
  content?: React.ReactNode;
}

const AnnotatingHeader: React.FunctionComponent<AnnotatingHeaderProps> = ({
  content,
}) => {
  const { annotationQueue } = useSMEFlow();

  return (
    <div className="flex flex-1 items-center justify-between">
      <h1 className="comet-title-s truncate break-words">
        {annotationQueue?.name}
      </h1>
      {content}
    </div>
  );
};

export default AnnotatingHeader;
