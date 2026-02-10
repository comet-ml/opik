import { StringParam, useQueryParam } from "use-query-params";
import { ContextType } from "@/types/custom-view";

export function useCustomViewParams() {
  const [contextTypeParam, setContextTypeParam] = useQueryParam(
    "contextType",
    StringParam,
  );
  const [selectedTraceId, setSelectedTraceId] = useQueryParam(
    "traceId",
    StringParam,
  );
  const [selectedThreadId, setSelectedThreadId] = useQueryParam(
    "threadId",
    StringParam,
  );
  const [model, setModel] = useQueryParam("model", StringParam);
  const [provider, setProvider] = useQueryParam("provider", StringParam);

  const contextType: ContextType = (contextTypeParam as ContextType) || "trace";

  const setContextType = (type: ContextType) => {
    setContextTypeParam(type);
    // Clear selections when switching context type
    if (type === "trace") {
      setSelectedThreadId(null);
    } else {
      setSelectedTraceId(null);
    }
  };

  return {
    contextType,
    setContextType,
    selectedTraceId: selectedTraceId || null,
    selectedThreadId: selectedThreadId || null,
    setSelectedTraceId,
    setSelectedThreadId,
    model: model || null,
    provider: provider || null,
    setModel,
    setProvider,
  };
}
