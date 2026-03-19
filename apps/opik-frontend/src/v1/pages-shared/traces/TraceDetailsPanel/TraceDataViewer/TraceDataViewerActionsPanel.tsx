import React, { useMemo } from "react";

import { Span, Trace } from "@/types/traces";
import { usePermissions } from "@/contexts/PermissionsContext";
import AddToDropdown from "@/v1/pages-shared/traces/AddToDropdown/AddToDropdown";
import {
  DetailsActionSection,
  DetailsActionSectionToggle,
  DetailsActionSectionValue,
} from "@/v1/pages-shared/traces/DetailsActionSection";
import { ButtonLayoutSize } from "@/v1/pages-shared/traces/DetailsActionSection";
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
  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();
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
      {canAnnotateTraceSpanThread && (
        <DetailsActionSectionToggle
          activeSection={activeSection}
          setActiveSection={setActiveSection}
          layoutSize={layoutSize}
          count={annotationCount}
          type={DetailsActionSection.Annotations}
        />
      )}
    </>
  );
};

export default TraceDataViewerActionsPanel;
