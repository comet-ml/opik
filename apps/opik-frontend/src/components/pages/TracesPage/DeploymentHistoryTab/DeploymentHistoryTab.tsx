import React from "react";

type DeploymentHistoryTabProps = {
  projectId: string;
};

const DeploymentHistoryTab: React.FC<DeploymentHistoryTabProps> = () => {
  return <div className="px-6 py-4">Deployment history</div>;
};

export default DeploymentHistoryTab;
