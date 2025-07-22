import React from "react";
import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
interface TraceAIViewerProps {
  traceId: string;
  spanId?: string;
  projectId: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
}

const TraceAIViewer: React.FC<TraceAIViewerProps> = ({
  traceId,
  spanId,
  projectId,
  activeSection,
  setActiveSection,
}) => {
  return (
    <DetailsActionSectionLayout
      title="Inspect trace (BETA)"
      closeTooltipContent="Close inspect trace"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
    >
      Hello world
    </DetailsActionSectionLayout>
  );
};

export default TraceAIViewer;
