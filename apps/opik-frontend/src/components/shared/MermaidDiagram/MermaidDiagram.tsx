import React, { useEffect, useState } from "react";
import mermaid from "mermaid";

mermaid.initialize({
  startOnLoad: false,
  htmlLabels: true,
  securityLevel: "loose",
});

type MermaidDiagramProps = {
  chart: string;
};

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ chart }) => {
  const [svg, setSvg] = useState<string>("");

  useEffect(() => {
    const renderChart = async () => {
      try {
        const { svg } = await mermaid.render("mermaid-diagram", chart);
        setSvg(svg);
      } catch (error) {
        console.error("Failed to render mermaid diagram", error);
      }
    };

    renderChart();
  }, [chart]);

  return (
    <div
      dangerouslySetInnerHTML={{
        __html: svg,
      }}
      className="mermaid flex size-full items-center justify-center"
    />
  );
};

export default MermaidDiagram;
