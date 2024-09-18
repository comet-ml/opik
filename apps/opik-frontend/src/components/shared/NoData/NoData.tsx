import React from "react";
import { Ban } from "lucide-react";
import { cn } from "@/lib/utils";

type NoDataProps = {
  title?: string;
  message?: string;
  className?: string;
};

const NoData: React.FunctionComponent<NoDataProps> = ({
  title,
  message = "No Data",
  className,
}) => {
  return (
    <div
      className={cn(
        "flex h-full min-h-96 flex-col items-center justify-center",
        className,
      )}
    >
      <Ban />
      {title && <h3 className="comet-title-s">{title}</h3>}
      {message}
    </div>
  );
};

export default NoData;
