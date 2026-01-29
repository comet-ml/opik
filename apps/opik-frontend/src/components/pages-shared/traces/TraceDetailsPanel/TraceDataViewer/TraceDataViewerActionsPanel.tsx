import React, { useCallback, useMemo, useState } from "react";
import { Play } from "lucide-react";

import { Span, Trace } from "@/types/traces";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddToDropdown from "@/components/pages-shared/traces/AddToDropdown/AddToDropdown";
import OpenInPlaygroundDialog from "@/components/pages-shared/traces/OpenInPlaygroundDialog";
import {
  DetailsActionSection,
  DetailsActionSectionToggle,
  DetailsActionSectionValue,
  ButtonLayoutSize,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { isObjectSpan } from "@/lib/traces";
import { canOpenInPlayground } from "@/lib/playground/extractPlaygroundData";
import useOpenInPlayground from "@/hooks/useOpenInPlayground";

type TraceDataViewerActionsPanelProps = {
  layoutSize: ButtonLayoutSize;
  data: Trace | Span;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
  treeData: Array<Trace | Span>;
};

const TraceDataViewerActionsPanel: React.FunctionComponent<
  TraceDataViewerActionsPanelProps
> = ({ data, activeSection, setActiveSection, layoutSize, treeData }) => {
  const [playgroundDialogOpen, setPlaygroundDialogOpen] = useState(false);
  const { openInPlayground, isPlaygroundEmpty } = useOpenInPlayground();

  const rows = useMemo(() => (data ? [data] : []), [data]);

  const annotationCount = data.feedback_scores?.length;
  const commentsCount = data.comments?.length;

  const isSpan = isObjectSpan(data);
  const dataType = isSpan ? "spans" : "traces";

  // Check if this item can be opened in playground
  const canOpen = canOpenInPlayground(data);

  const handleOpenInPlayground = useCallback(() => {
    setPlaygroundDialogOpen(true);
  }, []);

  const handleConfirmOpenInPlayground = useCallback(() => {
    openInPlayground(data, treeData);
  }, [openInPlayground, data, treeData]);

  const isSmall = layoutSize === ButtonLayoutSize.Small;

  return (
    <>
      <AddToDropdown
        getDataForExport={async () => rows}
        selectedRows={rows}
        dataType={dataType}
      />

      {canOpen && (
        <TooltipWrapper content="Open in Playground">
          <Button
            variant="outline"
            size={isSmall ? "icon-sm" : "sm"}
            onClick={handleOpenInPlayground}
          >
            <Play className="size-3.5" />
            {!isSmall && <span className="ml-1.5">Playground</span>}
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

      <OpenInPlaygroundDialog
        open={playgroundDialogOpen}
        setOpen={setPlaygroundDialogOpen}
        onConfirm={handleConfirmOpenInPlayground}
        selectedItem={data}
        treeData={treeData}
        isPlaygroundEmpty={isPlaygroundEmpty}
      />
    </>
  );
};

export default TraceDataViewerActionsPanel;
