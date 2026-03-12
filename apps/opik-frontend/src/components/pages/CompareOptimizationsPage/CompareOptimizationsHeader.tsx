import React, { useCallback, useMemo, useRef, useState } from "react";
import { Play, RotateCw, X } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import useOptimizationStopMutation from "@/api/optimizations/useOptimizationStopMutation";
import useAppStore from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { extractPromptData } from "@/lib/prompt";
import { OPTIMIZATION_PROMPT_KEY } from "@/constants/experiments";
import get from "lodash/get";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { convertMessages } from "@/components/pages-shared/shared/useSaveToPromptLibrary";

type CompareOptimizationsHeaderProps = {
  title: string;
  status?: OPTIMIZATION_STATUS;
  optimizationId?: string;
  isStudioOptimization?: boolean;
  canRerun?: boolean;
  bestExperiment?: Experiment | null;
};

const CompareOptimizationsHeader: React.FC<CompareOptimizationsHeaderProps> = ({
  title,
  status,
  optimizationId,
  isStudioOptimization,
  canRerun,
  bestExperiment,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { mutate: stopOptimization, isPending: isStoppingOptimization } =
    useOptimizationStopMutation();
  const [confirmDeployOpen, setConfirmDeployOpen] = useState(false);
  const resetKeyRef = useRef(0);

  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const isInProgress =
    status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const canStop = isStudioOptimization && optimizationId && isInProgress;

  const extractedPrompt = useMemo(
    () =>
      bestExperiment
        ? extractPromptData(
            get(bestExperiment.metadata ?? {}, OPTIMIZATION_PROMPT_KEY, null),
          )
        : null,
    [bestExperiment],
  );

  const canDeploy = Boolean(extractedPrompt) && !isInProgress;

  const handleStop = () => {
    if (!optimizationId) return;
    stopOptimization({ optimizationId });
  };

  const handleRerun = () => {
    if (!optimizationId) return;
    navigate({
      to: "/$workspaceName/optimizations/new",
      params: { workspaceName },
      search: { rerun: optimizationId },
    });
  };

  const handleDeployToPlayground = useCallback(() => {
    if (!extractedPrompt) return;

    if (extractedPrompt.type === "single") {
      const convertedMessages = convertMessages(extractedPrompt.data);
      loadPlayground({
        promptContent: JSON.stringify(convertedMessages, null, 2),
        templateStructure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
      });
    } else {
      // For multi-agent prompts, load all agents as separate Playground prompts
      const namedPrompts = Object.entries(extractedPrompt.data).map(
        ([name, messages]) => ({
          name,
          content: JSON.stringify(convertMessages(messages), null, 2),
        }),
      );
      loadPlayground({ namedPrompts });
    }
  }, [extractedPrompt, loadPlayground]);

  const handleDeployClick = useCallback(() => {
    if (!extractedPrompt) return;

    if (isPlaygroundEmpty) {
      handleDeployToPlayground();
    } else {
      resetKeyRef.current = resetKeyRef.current + 1;
      setConfirmDeployOpen(true);
    }
  }, [extractedPrompt, isPlaygroundEmpty, handleDeployToPlayground]);

  return (
    <>
      <div className="mb-4 flex min-h-8 flex-nowrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h1 className="comet-title-l truncate break-words">{title}</h1>
          {status && (
            <Tag
              variant={STATUS_TO_VARIANT_MAP[status]}
              size="md"
              className="capitalize"
            >
              {status}
            </Tag>
          )}
        </div>
        {(canStop || canRerun || canDeploy) && (
          <div className="flex items-center gap-2">
            {canDeploy && (
              <TooltipWrapper content="Deploy best prompt to Playground">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleDeployClick}
                  disabled={isPendingProviderKeys}
                >
                  <Play className="mr-2 size-4" />
                  Run in Playground
                </Button>
              </TooltipWrapper>
            )}
            {canRerun && (
              <Button variant="outline" size="sm" onClick={handleRerun}>
                <RotateCw className="mr-2 size-4" />
                Rerun
              </Button>
            )}
            {canStop && (
              <Button
                variant="destructive"
                size="sm"
                onClick={handleStop}
                disabled={isStoppingOptimization}
              >
                <X className="mr-2 size-4" />
                Stop Execution
              </Button>
            )}
          </div>
        )}
      </div>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={confirmDeployOpen}
        setOpen={setConfirmDeployOpen}
        onConfirm={handleDeployToPlayground}
        title="Run in Playground"
        description="Loading the best prompt into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Run in Playground"
      />
    </>
  );
};

export default CompareOptimizationsHeader;
