import React from "react";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import type { ViewTree } from "@/lib/data-view/core/types";

interface TreeJsonViewerProps {
  data: ViewTree;
}

const TreeJsonViewer: React.FC<TreeJsonViewerProps> = ({ data }) => {
  return (
    <div className="min-w-0 flex-1">
      <SyntaxHighlighter data={data} maxHeight="400px" />
    </div>
  );
};

export default TreeJsonViewer;
