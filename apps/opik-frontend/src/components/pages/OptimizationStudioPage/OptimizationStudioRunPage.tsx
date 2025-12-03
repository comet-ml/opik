import React, { useEffect, useMemo } from "react";
import { useQueryParam, StringParam } from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";
import { EXPERIMENT_TYPE } from "@/types/datasets";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import {
  OptimizationStudioProvider,
  useOptimizationStudioContext,
} from "./OptimizationStudioContext";
import OptimizationStudioActions from "./OptimizationStudioActions";
import ConfigureOptimizationSection from "./ConfigureOptimizationSection/ConfigureOptimizationSection";
import ObserveOptimizationSection from "@/components/pages/OptimizationStudioPage/ObserveOptimizationSection/ObserveOptimizationSection";
import { DEMO_TEMPLATES } from "@/constants/optimizations";
import useOptimizationStudioById from "@/api/optimizations/useOptimizationStudioById";
import { Tag } from "@/components/ui/tag";
import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";
const REFETCH_INTERVAL = 30000;
const ACTIVE_REFETCH_INTERVAL = 3000;
const MAX_EXPERIMENTS_LOADED = 1000;

const OptimizationStudioRunPageContent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const [optimizationId] = useQueryParam("optimizationId", StringParam);
  const [templateId] = useQueryParam("template", StringParam);
  const {
    setActiveOptimization,
    setExperiments,
    setTemplateData,
    activeOptimization,
  } = useOptimizationStudioContext();

  const { data: optimizationStudio, isPending: isOptimizationPending } =
    useOptimizationStudioById(
      {
        optimizationId: optimizationId!,
      },
      {
        enabled: Boolean(optimizationId),
        placeholderData: keepPreviousData,
        refetchInterval: (query) => {
          if (!optimizationId) return false;
          const status = query.state.data?.status;
          if (
            status === OPTIMIZATION_STATUS.RUNNING ||
            status === OPTIMIZATION_STATUS.INITIALIZED
          ) {
            return ACTIVE_REFETCH_INTERVAL;
          }
          return REFETCH_INTERVAL;
        },
      },
    );

  const isActiveOptimization =
    activeOptimization?.status === OPTIMIZATION_STATUS.RUNNING ||
    activeOptimization?.status === OPTIMIZATION_STATUS.INITIALIZED;

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
        refetchInterval: optimizationId
          ? isActiveOptimization
            ? ACTIVE_REFETCH_INTERVAL
            : REFETCH_INTERVAL
          : false,
      },
    );

  const experiments = useMemo(
    () => experimentsData?.content ?? [],
    [experimentsData?.content],
  );

  useEffect(() => {
    return () => setBreadcrumbParam("optimizationStudioRun", "run", "");
  }, [setBreadcrumbParam]);

  useEffect(() => {
    if (activeOptimization?.name) {
      setBreadcrumbParam(
        "optimizationStudioRun",
        "run",
        `${activeOptimization.name}`,
      );
    } else {
      setBreadcrumbParam("optimizationStudioRun", "run", "Start optimization");
    }
  }, [activeOptimization?.name]);

  useEffect(() => {
    if (templateId) {
      const template = DEMO_TEMPLATES.find((t) => t.id === templateId);
      setTemplateData(template || null);
    } else {
      setTemplateData(null);
    }
  }, [templateId, setTemplateData]);

  useEffect(() => {
    setActiveOptimization(optimizationStudio || null);
  }, [optimizationStudio, setActiveOptimization]);

  useEffect(() => {
    setExperiments(experiments);
  }, [experiments, setExperiments]);

  const isPending = isOptimizationPending || isExperimentsPending;

  if (isPending && optimizationId) {
    return <Loader />;
  }

  return (
    <div className="py-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Optimization studio
        </h1>
        <OptimizationStudioActions />
      </div>
      {activeOptimization && (
        <div className="-mt-3 mb-4 flex items-center gap-2 text-muted-slate">
          <span className="truncate">{activeOptimization.name}</span>
          <Tag
            variant={STATUS_TO_VARIANT_MAP[activeOptimization.status]}
            className="capitalize"
            size="md"
          >
            {activeOptimization.status}
          </Tag>
        </div>
      )}
      <div className="flex flex-col gap-6 lg:flex-row">
        <div className="flex w-full flex-col gap-4 lg:w-1/3">
          <ConfigureOptimizationSection />
        </div>

        <div className="flex w-full flex-col lg:w-2/3">
          <ObserveOptimizationSection />
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
