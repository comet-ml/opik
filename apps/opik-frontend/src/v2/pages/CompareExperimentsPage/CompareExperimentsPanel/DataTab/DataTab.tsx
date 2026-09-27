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
    /* Bounded to the tab's scroll viewport rather than growing with its
       content: min-h-full let a long conversation stretch the whole row, so the
       columns scrolled together as one block and the scores and comments
       sections drifted with them. Each column now scrolls on its own instead. */
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="compare-vetical-sidebar"
      style={{ overflow: "unset" }}
      className="h-full min-h-0"
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
