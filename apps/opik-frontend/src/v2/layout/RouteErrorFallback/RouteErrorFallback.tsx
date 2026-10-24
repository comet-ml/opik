import React from "react";
import { ErrorComponent, ErrorComponentProps } from "@tanstack/react-router";
import Loader from "@/shared/Loader/Loader";
import { useIsChunkReloadPending } from "@/store/ChunkReloadStore";

// While a stale-chunk recovery decides whether to reload, the spinner hides the
// error it is about to replace; once it decides not to, the real error shows.
const RouteErrorFallback: React.FC<ErrorComponentProps> = (props) => {
  const reloadPending = useIsChunkReloadPending();

  return reloadPending ? <Loader /> : <ErrorComponent {...props} />;
};

export default RouteErrorFallback;
