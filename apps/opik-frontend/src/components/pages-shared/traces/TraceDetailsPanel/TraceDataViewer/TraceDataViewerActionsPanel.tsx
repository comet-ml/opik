import React, { useMemo, useRef, useState } from "react";
import DatabasePlus from "@/icons/database-plus.svg?react";

import { Span, Trace } from "@/types/traces";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import {
  DetailsActionSection,
  DetailsActionSectionToggle,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { ButtonLayoutSize } from "@/components/pages-shared/traces/DetailsActionSection";

type TraceDataViewerActionsPanelProps = {
  layoutSize: ButtonLayoutSize;
  data: Trace | Span;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
};

const TraceDataViewerActionsPanel: React.FunctionComponent<
  TraceDataViewerActionsPanelProps
> = ({ data, activeSection, setActiveSection, layoutSize }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const rows = useMemo(() => (data ? [data] : []), [data]);

  const annotationCount = data.feedback_scores?.length;
  const commentsCount = data.comments?.length;

  return (
    <>
      <AddToDatasetDialog
        key={resetKeyRef.current}
        rows={rows}
        open={open}
        setOpen={setOpen}
      />

      <TooltipWrapper content="Add to dataset">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        >
          <DatabasePlus className="size-3.5" />
          {layoutSize === "lg" && <div className="pl-1">Add to dataset</div>}
        </Button>
      </TooltipWrapper>

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
