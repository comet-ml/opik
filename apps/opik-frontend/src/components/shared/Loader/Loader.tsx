import React from "react";
import { cn } from "@/lib/utils";
import { Spinner } from "@/components/ui/spinner";

type LoaderProps = {
  message?: React.ReactNode;
  className?: string;
};

const Loader: React.FunctionComponent<LoaderProps> = ({
  message = "Loading",
  className = "min-h-96",
}) => {
  return (
    <div
      className={cn(
        "flex h-full flex-col items-center justify-center",
        className,
      )}
    >
      <Spinner className={cn(message && "mb-2")} />
      {message}
    </div>
  );
};

export default Loader;
