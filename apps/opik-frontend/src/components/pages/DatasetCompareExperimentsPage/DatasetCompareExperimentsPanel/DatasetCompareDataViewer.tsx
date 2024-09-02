import React from "react";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import NoData from "@/components/shared/NoData/NoData";

type DatasetCompareDataViewerProps = {
  title: string;
  code?: object;
};

const DatasetCompareDataViewer: React.FunctionComponent<
  DatasetCompareDataViewerProps
> = ({ title, code }) => {
  const renderContent = () => {
    if (!code) {
      return <NoData />;
    }

    return <SyntaxHighlighter data={code} />;
  };

  return (
    <div className="size-full overflow-auto p-6">
      <div className="min-w-72 max-w-full overflow-x-hidden">
        <h2 className="comet-title-m mb-4 truncate">{title}</h2>
        {renderContent()}
      </div>
    </div>
  );
};

export default DatasetCompareDataViewer;
