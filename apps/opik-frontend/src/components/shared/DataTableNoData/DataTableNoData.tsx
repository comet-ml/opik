import React from "react";

type DataTableNoDataProps = {
  title: string;
  children?: React.ReactNode;
};

const DataTableNoData: React.FunctionComponent<DataTableNoDataProps> = ({
  title,
  children,
}) => {
  return (
    <div className="flex min-h-28 flex-col items-center justify-center">
      <span className="text-muted-slate">{title}</span>
      <div className="flex flex-col">{children}</div>
    </div>
  );
};

export default DataTableNoData;
