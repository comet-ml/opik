import React from "react";
import CustomSuccess from "@/icons/custom-success.svg?react";
import { ArrowRight } from "lucide-react";

type LoggedDataStatusProps = {
  status: "waiting" | "logged";
  onExplore?: () => void;
};

const LoggedDataStatus: React.FC<LoggedDataStatusProps> = ({
  status,
  onExplore,
}) => {
  if (status === "logged") {
    return (
      <button
        type="button"
        onClick={onExplore}
        className="comet-body-s-accented inline-flex h-8 shrink-0 cursor-pointer items-center rounded-md border border-chart-green px-3 text-chart-green hover:opacity-80"
      >
        <CustomSuccess className="mr-1.5 size-3.5 [&_path]:fill-chart-green" />
        Traces received, explore Opik
        <ArrowRight className="ml-1.5 size-3.5" />
      </button>
    );
  }

  return (
    <div className="comet-body-s-accented inline-flex h-8 shrink-0 items-center rounded-md border border-primary px-3 text-primary">
      <div className="relative mr-3">
        <div className="size-2 rounded-full bg-primary"></div>
        <div className="absolute inset-0 size-2 animate-ping rounded-full bg-primary opacity-75"></div>
      </div>
      Waiting for data
    </div>
  );
};

export default LoggedDataStatus;
