import React from "react";
import { DatasetItem } from "@/types/datasets";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import NoData from "@/components/shared/NoData/NoData";

interface DataTabProps {
  data: DatasetItem["data"] | undefined;
}

const DataTab = ({ data }: DataTabProps) => {
  return data ? (
    <div className="pb-8">
      <SyntaxHighlighter data={data} />
    </div>
  ) : (
    <NoData />
  );
};

export default DataTab;
