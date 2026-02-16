import React from "react";
import { PromptPageExperimentsTabTrigger } from "@/components/pages/PromptPage/PromptPageExperimentsTab";
import useUserPermission from "./useUserPermission";

export const TabTrigger: React.FC = () => {
  const { canViewExperiments } = useUserPermission();

  return (
    <PromptPageExperimentsTabTrigger canViewExperiments={canViewExperiments} />
  );
};

export default TabTrigger;
