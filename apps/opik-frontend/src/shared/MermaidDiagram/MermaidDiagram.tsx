import React from "react";

import MermaidDiagramError from "@/shared/MermaidDiagram/MermaidDiagramError";
import useMermaidSvg, {
  MERMAID_CONTAINER_CLASSNAME,
} from "@/shared/MermaidDiagram/useMermaidSvg";

type MermaidDiagramProps = {
  chart: string;
};

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ chart }) => {
  const { svg, hasError } = useMermaidSvg(chart);

  if (hasError) return <MermaidDiagramError />;

  return (
    <div
      dangerouslySetInnerHTML={{
        __html: svg,
      }}
      className={MERMAID_CONTAINER_CLASSNAME}
    />
  );
};

export default MermaidDiagram;
