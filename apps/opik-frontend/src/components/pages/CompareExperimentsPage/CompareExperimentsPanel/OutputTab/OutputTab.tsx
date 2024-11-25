import React, { useEffect } from "react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";

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
      <React.Fragment key={experimentItem.id}>
        {/*ALEX*/}
        <ResizablePanel className="min-w-72" style={{ overflow: "unset" }}>
          <CompareExperimentsViewer
            experimentItem={experimentItem}
            openTrace={openTrace}
          />
        </ResizablePanel>

        {idx !== experimentItems.length - 1 ? <ResizableHandle /> : null}
      </React.Fragment>
    ));
  };

  // ALEX
  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="compare-vetical-sidebar"
      style={{ height: "unset", overflow: "unset" }}
    >
      <ResizablePanel defaultSize={30} className="min-w-72">
        <ExperimentDataset data={data} />
      </ResizablePanel>
      <ResizableHandle />
      {renderExperimentsSection()}
    </ResizablePanelGroup>
  );
};

export default OutputTab;
