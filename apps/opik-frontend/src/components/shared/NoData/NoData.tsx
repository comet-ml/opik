import React from "react";
import { Ban } from "lucide-react";
import { cn } from "@/lib/utils";

type NoDataProps = {
  icon?: React.ReactNode;
  title?: string;
  message?: string;
  className?: string;
  children?: React.ReactNode;
};

const NoData: React.FunctionComponent<NoDataProps> = ({
  icon = <Ban className="text-muted-slate" />,
  title,
  message = "No Data",
  className,
  children,
}) => {
  return (
    <div
      className={cn(
        "flex h-full min-h-96 flex-col items-center justify-center",
        className,
      )}
    >
      {icon}
      {title && (
        <h3 className="comet-body-accented mb-1 text-foreground">{title}</h3>
      )}
      {message && (
        <div className="comet-body-small text-muted-slate">{message}</div>
      )}
      {children}
    </div>
  );
};

export default NoData;
