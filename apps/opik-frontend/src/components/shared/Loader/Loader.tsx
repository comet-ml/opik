import React from "react";
import { Spinner } from "@/components/ui/spinner";

type LoaderProps = {
  message?: string;
};

const Loader: React.FunctionComponent<LoaderProps> = ({
  message = "Loading",
}) => {
  return (
    <div className="flex h-full min-h-96 flex-col items-center justify-center">
      <Spinner />
      {message}
    </div>
  );
};

export default Loader;
