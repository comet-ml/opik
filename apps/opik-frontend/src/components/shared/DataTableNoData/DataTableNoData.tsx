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
    <div className="flex min-h-48 flex-col items-center justify-center gap-2 p-6">
      <span className="whitespace-pre-wrap break-words text-center text-muted-slate">
        {title}
      </span>
      <div className="flex flex-col">{children}</div>
    </div>
  );
};

export default DataTableNoData;
