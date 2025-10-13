import React, { useCallback, useEffect, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { Plus } from "lucide-react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import { LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import { Button } from "@/components/ui/button";
import OptimizationConfig from "@/components/pages/OptimizationStudioRunPage/OptimizationConfig/OptimizationConfig";
import OptimizerRunOutput from "@/components/pages/OptimizationStudioRunPage/OptimizerRunOutput/OptimizerRunOutput";
import useOptimizationStudioRunCreateMutation from "@/api/optimization-studio/useOptimizationStudioRunCreateMutation";
import useOptimizationStudioRunById from "@/api/optimization-studio/useOptimizationStudioRunById";
import { OPTIMIZATION_ALGORITHM } from "@/types/optimization-studio";
import { Separator } from "@/components/ui/separator";
import useDatasetById from "@/api/datasets/useDatasetById";
import useAppStore from "@/store/AppStore";

const PAGE_FULL_HEIGHT_DIFFERENCE = 148;

const containerStyle = {
  "--page-difference": PAGE_FULL_HEIGHT_DIFFERENCE + "px",
};

const OptimizationStudioRunPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [runId, setRunId] = useQueryParam("runId", StringParam);
  const [messages, setMessages] = useState<LLMMessage[]>([
    generateDefaultLLMPromptMessage(),
  ]);
  const [datasetId, setDatasetId] = useState<string>("");
  const [algorithm, setAlgorithm] = useState<OPTIMIZATION_ALGORITHM>(
    OPTIMIZATION_ALGORITHM.HIERARCHICAL_REFLECTIVE
  );
  const createRunMutation = useOptimizationStudioRunCreateMutation();

  // Fetch run details if runId is present
  const { data: run, isLoading: isLoadingRun, error: runError } = useOptimizationStudioRunById(
    {
      runId: runId || "",
      workspaceName,
    },
    {
      enabled: !!runId,
    }
  );

  // Debug logs
  useEffect(() => {
    console.log("DEBUG - runId from URL:", runId);
    console.log("DEBUG - isLoadingRun:", isLoadingRun);
    console.log("DEBUG - runError:", runError);
    console.log("DEBUG - run data:", run);
  }, [runId, isLoadingRun, runError, run]);

  // Load run configuration when run data is fetched
  useEffect(() => {
    console.log("DEBUG - Loading run configuration, run:", run);
    if (run) {
      console.log("DEBUG - Setting messages:", run.prompt);
      console.log("DEBUG - Setting datasetId:", run.dataset_id);
      console.log("DEBUG - Setting algorithm:", run.algorithm);
      setMessages(run.prompt as LLMMessage[]);
      setDatasetId(run.dataset_id);
      setAlgorithm(run.algorithm);
    }
  }, [run]);

  // Fetch dataset details to get the name
  const { data: dataset } = useDatasetById({
    datasetId,
    workspaceName,
  });

  const handleAddMessage = useCallback(() => {
    const newMessage = generateDefaultLLMPromptMessage();
    setMessages((prev) => [...prev, newMessage]);
  }, []);

  const handleRunOptimization = useCallback(() => {
    if (!datasetId || !dataset) {
      return;
    }

    // Reset runId in URL before starting new run
    setRunId(undefined);

    const prompt = messages.map(({ content, role }) => ({
      content,
      role,
    }));

    createRunMutation.mutate(
      {
        run: {
          name: `Optimization Run ${new Date().toISOString()}`,
          dataset_id: datasetId,
          dataset_name: dataset.name,
          prompt,
          algorithm,
          metric: "default_metric",
        },
      },
      {
        onSuccess: (data) => {
          if (data.id) {
            setRunId(data.id);
          }
        },
      }
    );
  }, [messages, datasetId, dataset, algorithm, createRunMutation, setRunId]);

  return (
    <div className="flex h-full w-full flex-col pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Optimization Studio
        </h1>
        <Button
          variant="default"
          size="sm"
          onClick={handleRunOptimization}
          disabled={!datasetId || !dataset || createRunMutation.isPending}
        >
          {createRunMutation.isPending ? "Starting..." : "Run Optimization"}
        </Button>
      </div>
      <div
        style={containerStyle as React.CSSProperties}
        className="h-[calc(100vh-var(--page-difference))] w-full"
      >
        <ResizablePanelGroup direction="horizontal">
          <ResizablePanel defaultSize={50} minSize={20}>
            <div className="flex h-full flex-col overflow-auto mr-4">
              <div className="mb-4 flex items-center justify-between">
                <h2 className="comet-body-s-accented">Prompt</h2>
              </div>
              <LLMPromptMessages
                messages={messages}
                onChange={setMessages}
                onAddMessage={handleAddMessage}
                hidePromptActions={true}
              />
            </div>
          </ResizablePanel>
          <ResizableHandle withHandle />
          <ResizablePanel defaultSize={50} minSize={20}>
            <div className="h-full overflow-auto ml-4 flex flex-col gap-4">
              <OptimizationConfig
                datasetId={datasetId}
                onDatasetChange={setDatasetId}
                algorithm={algorithm}
                onAlgorithmChange={setAlgorithm}
              />
              <Separator className="bg-border opacity-50" />
              <OptimizerRunOutput runId={runId ?? undefined} />
            </div>
          </ResizablePanel>
        </ResizablePanelGroup>
      </div>
    </div>
  );
};

export default OptimizationStudioRunPage;
