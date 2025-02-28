import React from "react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";

import { DatasetItem, ExperimentItem } from "@/types/datasets";
import CompareExperimentsViewer from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer";
import { OnChangeFn } from "@/types/shared";
import ExperimentDataset from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/ExperimentDataset";

interface DataTabProps {
  data: DatasetItem["data"] | undefined;
  experimentItems: ExperimentItem[];
  openTrace: OnChangeFn<string>;
}

const DataTab = ({ data, experimentItems, openTrace }: DataTabProps) => {
  const renderExperimentsSection = () => {
    return experimentItems.map((experimentItem, idx) => (
      <React.Fragment key={experimentItem.id}>
        <ResizablePanel className="min-w-72" style={{ overflow: "unset" }}>
          <CompareExperimentsViewer
            experimentItem={experimentItem}
            openTrace={openTrace}
            sectionIdx={idx}
          />
        </ResizablePanel>

        {idx !== experimentItems.length - 1 ? <ResizableHandle /> : null}
      </React.Fragment>
    ));
  };

  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="compare-vetical-sidebar"
      style={{ height: "unset", overflow: "unset" }}
      className="min-h-full"
    >
      <ResizablePanel defaultSize={30} className="min-w-72">
        <ExperimentDataset data={data} />
      </ResizablePanel>
      <ResizableHandle />
      {renderExperimentsSection()}
    </ResizablePanelGroup>
  );
};

export default DataTab;
