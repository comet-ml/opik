import React from "react";
import {
  PromptPageExperimentsTabContent,
  PromptPageExperimentsTabProps,
} from "@/components/pages/PromptPage/PromptPageExperimentsTab";
import useUserPermission from "./useUserPermission";

export const TabContent: React.FC<PromptPageExperimentsTabProps> = (props) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <PromptPageExperimentsTabContent
      {...props}
      canViewExperiments={canViewExperiments}
    />
  );
};

export default TabContent;
