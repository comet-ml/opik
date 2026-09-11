import React from "react";
import { usePrewarmAssistantCompute } from "@/plugins/comet/useAssistantBackend";

const AssistantPrewarmer: React.FC = () => {
  usePrewarmAssistantCompute();
  return null;
};

export default AssistantPrewarmer;
