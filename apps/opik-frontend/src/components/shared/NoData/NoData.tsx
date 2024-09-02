import React from "react";
import { Ban } from "lucide-react";

type NoDataProps = {
  title?: string;
  message?: string;
};

const NoData: React.FunctionComponent<NoDataProps> = ({
  title,
  message = "No Data",
}) => {
  return (
    <div className="flex h-full min-h-96 flex-col items-center justify-center">
      <Ban />
      {title && <h3 className="comet-title-s">{title}</h3>}
      {message}
    </div>
  );
};

export default NoData;
