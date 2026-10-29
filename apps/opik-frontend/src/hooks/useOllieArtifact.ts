import { useEffect, useRef } from "react";
import { ArtifactHandler, registerArtifactHandler } from "@/lib/ollieArtifacts";

const useOllieArtifact = (
  kind: string,
  handler: ArtifactHandler,
  enabled = true,
) => {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    if (!enabled) return;
    return registerArtifactHandler(kind, (proposal) =>
      handlerRef.current(proposal),
    );
  }, [kind, enabled]);
};

export default useOllieArtifact;
