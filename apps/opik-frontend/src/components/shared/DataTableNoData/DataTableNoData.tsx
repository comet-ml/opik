import React from "react";

type DataTableNoDataProps = {
  title: string;
};

const DataTableNoData: React.FunctionComponent<DataTableNoDataProps> = ({
  title,
}) => {
  return (
    <div className="flex h-28 items-center justify-center">
      <span className="text-muted-slate">{title}</span>
    </div>
  );
};

export default DataTableNoData;
