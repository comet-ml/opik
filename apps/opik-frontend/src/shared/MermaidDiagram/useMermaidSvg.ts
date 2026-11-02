import { useEffect, useId, useRef, useState } from "react";
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
  svgId: string;
  hasError: boolean;
};

const useMermaidSvg = (chart: string) => {
  const [state, setState] = useState<MermaidSvgState>({
    svg: "",
    svgId: "",
    hasError: false,
  });
  const id = useId();
  const diagramId = `mermaid-diagram-${id.replace(/:/g, "")}`;
  const renderCountRef = useRef(0);

  useEffect(() => {
    let isCancelled = false;
    renderCountRef.current += 1;
    const svgId = `${diagramId}-${renderCountRef.current}`;

    const renderChart = async () => {
      try {
        const isValid = await mermaid.parse(chart, { suppressErrors: true });
        if (!isValid) throw new Error("Invalid mermaid diagram");

        const { svg } = await mermaid.render(svgId, chart);
        if (!isCancelled) setState({ svg, svgId, hasError: false });
      } catch (error) {
        document.getElementById(`d${svgId}`)?.remove();
        if (!isCancelled) setState({ svg: "", svgId, hasError: true });
        console.error("Failed to render mermaid diagram", error);
      }
    };

    renderChart();

    return () => {
      isCancelled = true;
    };
  }, [chart, diagramId]);

  return state;
};

export default useMermaidSvg;
