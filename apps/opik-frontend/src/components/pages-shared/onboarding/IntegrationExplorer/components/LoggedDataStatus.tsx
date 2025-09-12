import React from "react";
import CustomSuccess from "@/icons/custom-success.svg?react";

type LoggedDataStatusProps = {
  status: "waiting" | "logged";
};

const LoggedDataStatus: React.FC<LoggedDataStatusProps> = ({ status }) => {
  if (status === "logged") {
    return (
      <div className="flex shrink-0 items-center gap-1.5 rounded border border-primary bg-background px-3 py-1.5">
        <CustomSuccess />
        <span className="comet-body-s-accented text-primary">
          Receiving data
        </span>
      </div>
    );
  }
  return (
    <div className="flex shrink-0 items-center gap-2 rounded border border-primary bg-background px-3 py-1.5">
      <div className="relative">
        <div className="size-2 rounded-full bg-primary"></div>
        <div className="absolute inset-0 size-2 animate-ping rounded-full bg-primary opacity-75"></div>
      </div>
      <span className="comet-body-s-accented text-primary">
        Waiting for data
      </span>
    </div>
  );
};

export default LoggedDataStatus;
