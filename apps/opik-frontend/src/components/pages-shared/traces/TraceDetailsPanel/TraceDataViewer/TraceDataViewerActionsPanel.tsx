import React, { useMemo, useRef, useState } from "react";
import DatabasePlus from "@/icons/database-plus.svg?react";
import { ListChecks } from "lucide-react";

import { Span, Trace } from "@/types/traces";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import AddToQueueDialog from "@/components/pages-shared/traces/AddToQueueDialog/AddToQueueDialog";
import {
  DetailsActionSection,
  DetailsActionSectionToggle,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { ButtonLayoutSize } from "@/components/pages-shared/traces/DetailsActionSection";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

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
  const [open, setOpen] = useState<boolean | number>(false);

  const rows = useMemo(() => (data ? [data] : []), [data]);
  
  const annotationQueuesEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.ANNOTATION_QUEUES_ENABLED
  );

  const annotationCount = data.feedback_scores?.length;
  const commentsCount = data.comments?.length;

  return (
    <>
      <AddToDatasetDialog
        key={`dataset-${resetKeyRef.current}`}
        rows={rows}
        open={open === 1}
        setOpen={setOpen}
      />
      <AddToQueueDialog
        key={`queue-${resetKeyRef.current}`}
        rows={rows}
        open={open === 2}
        setOpen={setOpen}
        type="traces"
      />

      <TooltipWrapper content="Add to dataset">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(1);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        >
          <DatabasePlus className="size-3.5" />
          {layoutSize === "lg" && <div className="pl-1">Add to dataset</div>}
        </Button>
      </TooltipWrapper>
      
      {annotationQueuesEnabled && (
        <TooltipWrapper content="Add to annotation queue">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            className="bg-primary-50 border-primary-200 text-primary-700 hover:bg-primary-100"
          >
            <ListChecks className="size-3.5" />
            {layoutSize === "lg" && <div className="pl-1">Add to Annotation Queue</div>}
          </Button>
        </TooltipWrapper>
      )}

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
