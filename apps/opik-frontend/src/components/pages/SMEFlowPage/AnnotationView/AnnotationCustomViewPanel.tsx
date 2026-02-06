import React from "react";
import { Trace } from "@/types/traces";
import { ViewTree, DataViewProvider, Renderer } from "@/lib/data-view";
import { customViewRegistry } from "@/components/shared/data-view-widgets";

interface AnnotationCustomViewPanelProps {
  trace: Trace;
  viewTree: ViewTree;
}

const AnnotationCustomViewPanel: React.FC<AnnotationCustomViewPanelProps> = ({
  trace,
  viewTree,
}) => {
  // Check if the tree has a valid root and nodes
  if (
    !viewTree.root ||
    !viewTree.nodes ||
    Object.keys(viewTree.nodes).length === 0
  ) {
    return (
      <div className="flex items-center justify-center py-12 text-center">
        <div>
          <div className="comet-body-s mb-2 text-muted-slate">
            No Widgets Available
          </div>
          <div className="comet-body-xs text-muted-slate">
            The saved view doesn&apos;t contain any widgets.
          </div>
        </div>
      </div>
    );
  }

  // Convert trace to source data format
  const sourceData = { ...trace };

  return (
    <div className="space-y-4">
      <DataViewProvider initialSource={sourceData} initialTree={viewTree}>
        <Renderer registry={customViewRegistry} />
      </DataViewProvider>
    </div>
  );
};

export default AnnotationCustomViewPanel;
