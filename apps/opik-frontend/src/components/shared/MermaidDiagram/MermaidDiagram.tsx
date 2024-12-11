import React, { useEffect } from "react";
import mermaid from "mermaid";

type MermaidDiagramProps = {
  chart: string;
};

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ chart }) => {
  useEffect(() => {
    mermaid.initialize({ startOnLoad: true });
    mermaid.contentLoaded();
  }, [chart]);

  return <div className="mermaid size-full">{chart}</div>;
};

export default MermaidDiagram;
