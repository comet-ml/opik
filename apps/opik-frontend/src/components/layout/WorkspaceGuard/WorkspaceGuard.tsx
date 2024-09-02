import React from "react";
import usePluginStore from "@/store/PluginsStore";
import PageLayout from "@/components/layout/PageLayout/PageLayout";
import Loader from "@/components/shared/Loader/Loader";

const WorkspaceGuard = () => {
  const WorkspacePreloader = usePluginStore(
    (state) => state.WorkspacePreloader,
  );

  if (!WorkspacePreloader) {
    return <Loader />;
  }

  return (
    <WorkspacePreloader>
      <PageLayout />
    </WorkspacePreloader>
  );
};

export default WorkspaceGuard;
