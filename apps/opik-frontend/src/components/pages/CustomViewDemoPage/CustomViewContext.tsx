import React, { createContext, useContext, useState, useEffect, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { Trace, Thread } from "@/types/traces";
import { CustomViewSchema, ViewSource, ContextType, ContextData } from "@/types/custom-view";
import useTraceById from "@/api/traces/useTraceById";
import useThreadById from "@/api/traces/useThreadById";
import { customViewStorage } from "@/lib/customViewStorage";

interface CustomViewContextValue {
  // Context type state
  contextType: ContextType;
  setContextType: (type: ContextType) => void;

  // Trace state
  selectedTraceId: string | null;
  traceData: Trace | null | undefined;
  isTraceLoading: boolean;
  isTraceError: boolean;
  setSelectedTraceId: (id: string | null) => void;

  // Thread state
  selectedThreadId: string | null;
  threadData: Thread | null | undefined;
  isThreadLoading: boolean;
  isThreadError: boolean;
  setSelectedThreadId: (id: string | null) => void;

  // Unified context data
  contextData: ContextData | null | undefined;
  isContextLoading: boolean;
  isContextError: boolean;

  // Model state
  model: string | null;
  provider: string | null;
  setModel: (model: string | null) => void;
  setProvider: (provider: string | null) => void;

  // View state
  viewSchema: CustomViewSchema | null;
  viewSource: ViewSource;
  setViewSchema: (schema: CustomViewSchema | null) => void;
  setViewSource: (source: ViewSource) => void;
}

const CustomViewContext = createContext<CustomViewContextValue | undefined>(
  undefined,
);

interface CustomViewProviderProps {
  children: React.ReactNode;
  projectId: string;
}

export const CustomViewProvider: React.FC<CustomViewProviderProps> = ({
  children,
  projectId,
}) => {
  // URL query params for context type, trace, thread, and model
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

  // Default to 'trace' if no context type is set
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

  // View schema state - persists across context changes
  const [viewSchema, setViewSchema] = useState<CustomViewSchema | null>(null);
  const [viewSource, setViewSource] = useState<ViewSource>("empty");

  // Fetch trace data
  const {
    data: traceData,
    isPending: isTraceLoading,
    isError: isTraceError,
  } = useTraceById(
    { traceId: selectedTraceId || "", stripAttachments: false },
    { enabled: contextType === "trace" && Boolean(selectedTraceId) },
  );

  // Fetch thread data
  const {
    data: threadData,
    isPending: isThreadLoading,
    isError: isThreadError,
  } = useThreadById(
    { projectId, threadId: selectedThreadId || "" },
    { enabled: contextType === "thread" && Boolean(selectedThreadId) },
  );

  // Unified context data based on context type
  const contextData = useMemo<ContextData | null | undefined>(() => {
    if (contextType === "trace") {
      return traceData;
    } else {
      return threadData;
    }
  }, [contextType, traceData, threadData]);

  const isContextLoading = contextType === "trace" ? isTraceLoading : isThreadLoading;
  const isContextError = contextType === "trace" ? isTraceError : isThreadError;

  // Load saved schema on mount or when projectId changes
  useEffect(() => {
    if (!projectId) return;

    const savedSchema = customViewStorage.load(projectId);
    if (savedSchema) {
      setViewSchema(savedSchema);
      setViewSource("saved");
    } else {
      // Reset to empty when switching to project without saved view
      if (viewSource !== "ai") {
        setViewSchema(null);
        setViewSource("empty");
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]); // Only depend on projectId

  const value: CustomViewContextValue = {
    contextType,
    setContextType,
    selectedTraceId: selectedTraceId || null,
    traceData,
    isTraceLoading,
    isTraceError,
    setSelectedTraceId,
    selectedThreadId: selectedThreadId || null,
    threadData,
    isThreadLoading,
    isThreadError,
    setSelectedThreadId,
    contextData,
    isContextLoading,
    isContextError,
    model: model || null,
    provider: provider || null,
    setModel,
    setProvider,
    viewSchema,
    viewSource,
    setViewSchema,
    setViewSource,
  };

  return (
    <CustomViewContext.Provider value={value}>
      {children}
    </CustomViewContext.Provider>
  );
};

export const useCustomViewContext = (): CustomViewContextValue => {
  const context = useContext(CustomViewContext);

  if (context === undefined) {
    throw new Error(
      "useCustomViewContext must be used within a CustomViewProvider",
    );
  }

  return context;
};
