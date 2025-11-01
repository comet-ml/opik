import React from "react";

const DatasetEmptyState = () => {
  return (
    <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
      <div className="comet-body-s-accented pb-1 text-foreground">
        No datasets available
      </div>
      <div className="comet-body-s text-muted-slate">
        Create a dataset with examples to evaluate your prompt on.
      </div>
    </div>
  );
};

export default DatasetEmptyState;
