import React, { useEffect, useMemo } from "react";
import { useQueryParam, StringParam } from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";
import { EXPERIMENT_TYPE } from "@/types/datasets";
import {
  OptimizationStudioProvider,
  useOptimizationStudioContext,
} from "./OptimizationStudioContext";
import OptimizationStudioActions from "./OptimizationStudioActions";
import ConfigureOptimizationSection from "./ConfigureOptimizationSection";

const REFETCH_INTERVAL = 30000;
const MAX_EXPERIMENTS_LOADED = 1000;

const OptimizationStudioRunPageContent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const [optimizationId] = useQueryParam("optimizationId", StringParam);
  const { setActiveOptimization, setExperiments } =
    useOptimizationStudioContext();

  const { data: optimization, isPending: isOptimizationPending } =
    useOptimizationById(
      { optimizationId: optimizationId! },
      {
        enabled: Boolean(optimizationId),
        placeholderData: keepPreviousData,
        refetchInterval: optimizationId ? REFETCH_INTERVAL : false,
      },
    );

  const { data: experimentsData, isPending: isExperimentsPending } =
    useExperimentsList(
      {
        workspaceName,
        optimizationId: optimizationId!,
        types: [EXPERIMENT_TYPE.TRIAL, EXPERIMENT_TYPE.MINI_BATCH],
        page: 1,
        size: MAX_EXPERIMENTS_LOADED,
      },
      {
        enabled: Boolean(optimizationId),
        placeholderData: keepPreviousData,
        refetchInterval: optimizationId ? REFETCH_INTERVAL : false,
      },
    );

  const experiments = useMemo(
    () => experimentsData?.content ?? [],
    [experimentsData?.content],
  );

  useEffect(() => {
    setBreadcrumbParam("optimizationStudioRun", "run", "Optimization studio");
    return () => setBreadcrumbParam("optimizationStudioRun", "run", "");
  }, [setBreadcrumbParam]);

  useEffect(() => {
    setActiveOptimization(optimization || null);
  }, [optimization, setActiveOptimization]);

  useEffect(() => {
    setExperiments(experiments);
  }, [experiments, setExperiments]);

  const isPending = isOptimizationPending || isExperimentsPending;

  if (isPending && optimizationId) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Optimization studio
        </h1>
        <OptimizationStudioActions />
      </div>
      <div className="flex flex-wrap-reverse gap-6">
        <div className="flex min-w-[480px] flex-1 flex-col">
          <div className="rounded-md border p-6">
            <h2 className="comet-title-m mb-4">Initial prompt</h2>
          </div>
        </div>

        <div className="flex min-w-[720px] flex-[1.5] flex-col">
          <ConfigureOptimizationSection />
        </div>
      </div>
    </div>
  );
};

const OptimizationStudioRunPage = () => {
  return (
    <OptimizationStudioProvider>
      <OptimizationStudioRunPageContent />
    </OptimizationStudioProvider>
  );
};

export default OptimizationStudioRunPage;
