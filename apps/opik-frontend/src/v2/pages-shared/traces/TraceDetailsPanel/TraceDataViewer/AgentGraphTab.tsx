import React from "react";
import { AgentGraphData } from "@/types/traces";
import MermaidDiagram from "@/shared/MermaidDiagram/MermaidDiagram";
import ZoomPanContainer from "@/shared/ZoomPanContainer/ZoomPanContainer";

type AgentGraphTabProps = {
  data: AgentGraphData;
};

const AgentGraphTab: React.FC<AgentGraphTabProps> = ({ data }) => {
  return (
    <ZoomPanContainer dialogTitle="Agent graph">
      <MermaidDiagram chart={data.data} />
    </ZoomPanContainer>
  );
};

export default AgentGraphTab;
