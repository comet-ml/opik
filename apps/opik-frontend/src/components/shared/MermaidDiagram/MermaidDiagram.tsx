import React, { useEffect, useState, useId } from "react";
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
  const id = useId();
  const diagramId = `mermaid-diagram-${id.replace(/:/g, "")}`;

  useEffect(() => {
    const renderChart = async () => {
      try {
        const { svg } = await mermaid.render(diagramId, chart);
        setSvg(svg);
      } catch (error) {
        console.error("Failed to render mermaid diagram", error);
      }
    };

    renderChart();
  }, [chart, diagramId]);

  return (
    <div
      dangerouslySetInnerHTML={{
        __html: svg,
      }}
      className="mermaid flex size-full [&>svg]:m-auto [&>svg]:size-auto [&>svg]:!max-h-full [&>svg]:!max-w-full"
    />
  );
};

export default MermaidDiagram;
