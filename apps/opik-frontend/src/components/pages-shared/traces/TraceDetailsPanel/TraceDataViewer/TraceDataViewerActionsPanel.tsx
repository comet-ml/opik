import React, { useMemo } from "react";

import { Span, Trace } from "@/types/traces";
import AddToDropdown from "@/components/pages-shared/traces/AddToDropdown/AddToDropdown";
import {
  DetailsActionSection,
  DetailsActionSectionToggle,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { ButtonLayoutSize } from "@/components/pages-shared/traces/DetailsActionSection";
import { isObjectSpan } from "@/lib/traces";

type TraceDataViewerActionsPanelProps = {
  layoutSize: ButtonLayoutSize;
  data: Trace | Span;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
};

const TraceDataViewerActionsPanel: React.FunctionComponent<
  TraceDataViewerActionsPanelProps
> = ({ data, activeSection, setActiveSection, layoutSize }) => {
  const rows = useMemo(() => (data ? [data] : []), [data]);

  const annotationCount = data.feedback_scores?.length;
  const commentsCount = data.comments?.length;

  const isSpan = isObjectSpan(data);
  const dataType = isSpan ? "spans" : "traces";

  return (
    <>
      <AddToDropdown
        getDataForExport={async () => rows}
        selectedRows={rows}
        dataType={dataType}
      />

      <DetailsActionSectionToggle
        activeSection={activeSection}
        setActiveSection={setActiveSection}
        layoutSize={layoutSize}
        count={commentsCount}
        type={DetailsActionSection.Comments}
      />
      <DetailsActionSectionToggle
        activeSection={activeSection}
        setActiveSection={setActiveSection}
        layoutSize={layoutSize}
        count={annotationCount}
        type={DetailsActionSection.Annotations}
      />
    </>
  );
};

export default TraceDataViewerActionsPanel;
