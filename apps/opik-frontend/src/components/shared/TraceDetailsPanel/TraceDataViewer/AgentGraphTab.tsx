import React from "react";
import { AgentGraphData } from "@/types/traces";
import MermaidDiagram from "@/components/shared/MermaidDiagram/MermaidDiagram";

import "reactflow/dist/style.css";

type AgentGraphTabProps = {
  data: AgentGraphData;
};

const AgentGraphTab: React.FC<AgentGraphTabProps> = ({ data }) => {
  return <MermaidDiagram chart={data.data} />;
};

export default AgentGraphTab;
