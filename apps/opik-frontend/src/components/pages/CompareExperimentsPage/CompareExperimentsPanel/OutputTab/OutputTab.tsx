import React from "react";
import { ResizablePanelGroup } from "@/components/ui/resizable";

import { DatasetItem, ExperimentItem } from "@/types/datasets";
import CompareExperimentsViewer from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer";
import { OnChangeFn } from "@/types/shared";
import ExperimentDataset from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/OutputTab/ExperimentDataset";

interface CompareExperimentsOutputTabProps {
  data: DatasetItem["data"] | undefined;
  experimentItems: ExperimentItem[];
  openTrace: OnChangeFn<string>;
}

const OutputTab = ({
  data,
  experimentItems,
  openTrace,
}: CompareExperimentsOutputTabProps) => {
  const renderExperimentsSection = () => {
    return experimentItems.map((experimentItem, idx) => (
      <div key={experimentItem.id} className="min-w-72 flex-1 border-l">
        <CompareExperimentsViewer
          experimentItem={experimentItem}
          openTrace={openTrace}
        />
      </div>
    ));
  };

  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="compare-vetical-sidebar"
      style={{ overflow: "unset" }}
    >
      <ExperimentDataset data={data} />
      {renderExperimentsSection()}
    </ResizablePanelGroup>
  );
};

export default OutputTab;
