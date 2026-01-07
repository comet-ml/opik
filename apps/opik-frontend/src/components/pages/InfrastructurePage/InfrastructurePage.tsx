import React from "react";

const InfrastructurePage: React.FunctionComponent = () => {
  // Default to localhost:3000 if not specified
  const grafanaUrl = import.meta.env.VITE_GRAFANA_URL || "http://localhost:3000";

  return (
    <div className="h-full w-full">
      <iframe
        src={grafanaUrl}
        className="h-full w-full border-0"
        title="Infrastructure Monitoring"
      />
    </div>
  );
};

export default InfrastructurePage;
