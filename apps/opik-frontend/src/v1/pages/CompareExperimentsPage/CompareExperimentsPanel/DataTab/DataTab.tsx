import React from "react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";

import { DatasetItem, ExperimentItem } from "@/types/datasets";
import CompareExperimentsViewer from "@/v1/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer";
import { OnChangeFn } from "@/types/shared";
import ExperimentDataset from "@/v1/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/ExperimentDataset";

interface DataTabProps {
  data?: DatasetItem["data"];
  experimentItems: ExperimentItem[];
  openTrace: OnChangeFn<string>;
  datasetItemId?: string;
}

const DataTab = ({
  data,
  experimentItems,
  openTrace,
  datasetItemId,
}: DataTabProps) => {
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
        <ExperimentDataset data={data} datasetItemId={datasetItemId} />
      </ResizablePanel>
      <ResizableHandle />
      {renderExperimentsSection()}
    </ResizablePanelGroup>
  );
};

export default DataTab;
