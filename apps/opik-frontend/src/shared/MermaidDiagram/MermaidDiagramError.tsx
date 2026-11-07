import React from "react";

import NoData from "@/shared/NoData/NoData";

const MermaidDiagramError: React.FC = () => (
  <NoData
    className="min-h-0"
    message="This graph can't be displayed"
    icon={null}
  />
);

export default MermaidDiagramError;
