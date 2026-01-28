import React from "react";
import { DataViewProvider } from "@/lib/data-view/react/DataViewProvider";
import { Renderer } from "@/lib/data-view/react/Renderer";
import { customViewRegistry } from "@/components/pages/CustomViewDemoPage/data-view-widgets";
import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

interface TreeRendererProps {
  tree: ViewTree;
  sourceData: SourceData;
}

const TreeRenderer: React.FC<TreeRendererProps> = ({ tree, sourceData }) => {
  return (
    <div className="min-w-0 flex-1 rounded-md border bg-background p-4">
      <DataViewProvider initialSource={sourceData} initialTree={tree}>
        <Renderer registry={customViewRegistry} />
      </DataViewProvider>
    </div>
  );
};

export default TreeRenderer;
