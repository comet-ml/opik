import { useEffect, useId, useState } from "react";
import mermaid from "mermaid";

mermaid.initialize({
  startOnLoad: false,
  htmlLabels: true,
  securityLevel: "antiscript",
});

export const MERMAID_CONTAINER_CLASSNAME =
  "mermaid flex size-full [&>svg]:m-auto [&>svg]:size-auto [&>svg]:!max-h-full [&>svg]:!max-w-full";

type MermaidSvgState = {
  svg: string;
  hasError: boolean;
};

const useMermaidSvg = (chart: string) => {
  const [state, setState] = useState<MermaidSvgState>({
    svg: "",
    hasError: false,
  });
  const id = useId();
  const diagramId = `mermaid-diagram-${id.replace(/:/g, "")}`;

  useEffect(() => {
    let isCancelled = false;

    const renderChart = async () => {
      try {
        const isValid = await mermaid.parse(chart, { suppressErrors: true });
        if (!isValid) throw new Error("Invalid mermaid diagram");

        const { svg } = await mermaid.render(diagramId, chart);
        if (!isCancelled) setState({ svg, hasError: false });
      } catch (error) {
        document.getElementById(`d${diagramId}`)?.remove();
        if (!isCancelled) setState({ svg: "", hasError: true });
        console.error("Failed to render mermaid diagram", error);
      }
    };

    renderChart();

    return () => {
      isCancelled = true;
    };
  }, [chart, diagramId]);

  return { ...state, diagramId };
};

export default useMermaidSvg;
