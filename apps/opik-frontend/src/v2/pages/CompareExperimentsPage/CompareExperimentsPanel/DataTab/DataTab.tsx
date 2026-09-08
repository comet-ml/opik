import React from "react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";

import { DatasetItem, ExperimentItem } from "@/types/datasets";
import CompareExperimentsViewer from "@/v2/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer";
import { OnChangeFn } from "@/types/shared";
import ExperimentDataset from "@/v2/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/ExperimentDataset";

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
    // One panel per slot: the index is stable across dataset items, unlike the
    // experiment item id, and unique, unlike the experiment id when an
    // experiment run contributes several items per dataset item.
    return experimentItems.map((experimentItem, idx) => (
      <React.Fragment key={idx}>
        <ResizablePanel
          order={idx + 1}
          className="min-w-72"
          style={{ overflow: "unset" }}
        >
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
      <ResizablePanel order={0} defaultSize={30} className="min-w-72">
        <ExperimentDataset data={data} datasetItemId={datasetItemId} />
      </ResizablePanel>
      <ResizableHandle />
      {renderExperimentsSection()}
    </ResizablePanelGroup>
  );
};

export default DataTab;
