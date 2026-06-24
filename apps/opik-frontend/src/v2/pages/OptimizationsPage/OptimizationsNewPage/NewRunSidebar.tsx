import React, { useEffect, useMemo, useRef, useState } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import useGetOrCreateDemoDataset from "@/api/datasets/useGetOrCreateDemoDataset";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useProjectById from "@/api/projects/useProjectById";
import {
  OptimizationConfigFormType,
  OptimizationConfigSchema,
  convertOptimizationStudioToFormData,
} from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import { OPTIMIZATION_DEMO_TEMPLATES } from "@/constants/optimizations";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { useModelOptions } from "@/v2/pages-shared/llm/PromptModelSelect/useModelOptions";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import Loader from "@/shared/Loader/Loader";
import OptimizationsNewPageContent from "./OptimizationsNewPageContent";

type NewRunSidebarProps = {
  onClose: () => void;
  /** Pre-fill from a demo template. */
  templateId?: string;
  /** Pre-fill by cloning an existing run's config. */
  rerunId?: string;
};

/**
 * The "New optimization run" form, hosted in a right sidebar over the runs
 * list (replaces the former full-page `/optimizations/new` route). Mount it only
 * while open so its provider/rerun queries don't run in the background and the
 * form is re-seeded fresh on each open.
 */
const NewRunSidebar: React.FC<NewRunSidebarProps> = ({
  onClose,
  templateId,
  rerunId,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { getOrCreateDataset } = useGetOrCreateDemoDataset();
  const { data: project } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );
  const datasetCreationRef = useRef<string | null>(null);
  const [isPreparingDataset, setIsPreparingDataset] = useState(false);

  useEffect(() => {
    trackEvent(OpikEvent.OPTIMIZATION_WIZARD_STARTED, {
      from_template: Boolean(templateId),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const templateData = useMemo(
    () => OPTIMIZATION_DEMO_TEMPLATES.find((t) => t.id === templateId) ?? null,
    [templateId],
  );

  const { data: rerunOptimization, isFetching: isRerunFetching } =
    useOptimizationById(
      { optimizationId: rerunId || "" },
      {
        enabled: Boolean(rerunId),
      },
    );

  const rerunData = useMemo(
    () => (rerunOptimization?.studio_config ? rerunOptimization : null),
    [rerunOptimization],
  );

  // Resolve the models the workspace can actually run, so the default model is
  // picked from real options instead of a hardcoded guess that gets reconciled
  // by an effect afterwards.
  const { providerModels } = useLLMProviderModelsData();
  const { data: providerKeysData } = useProviderKeys(
    { workspaceName },
    { staleTime: 1000 },
  );
  const configuredProvidersList = useMemo(
    () => providerKeysData?.content ?? [],
    [providerKeysData?.content],
  );
  const { freeModelOption, groupOptions } = useModelOptions(
    configuredProvidersList,
    providerModels,
    "",
  );
  const availableModels = useMemo(
    () => [
      ...(freeModelOption ? [freeModelOption.value] : []),
      ...groupOptions.flatMap((group) => group.options.map((o) => o.value)),
    ],
    [freeModelOption, groupOptions],
  );

  // Only resolve the default once providers are genuinely known: the keys query
  // has settled AND (no providers configured OR their models have loaded).
  // Otherwise "configured providers, models still loading" would briefly look
  // like "no models" and seed an empty model.
  const providersReady =
    Boolean(providerKeysData) &&
    (configuredProvidersList.length === 0 || availableModels.length > 0);

  const defaultValues = useMemo(() => {
    return convertOptimizationStudioToFormData(
      rerunData || templateData,
      providersReady ? availableModels : [],
    );
  }, [rerunData, templateData, providersReady, availableModels]);

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    // `values` (not `defaultValues`) re-seeds the form when the resolved model
    // lands; `keepDirtyValues` preserves a model the user already picked.
    values: defaultValues,
    resetOptions: { keepDirtyValues: true },
    mode: "onChange",
  });

  // Create demo dataset when template with dataset_items is selected
  useEffect(() => {
    const internalCreateOrGetDataset = async () => {
      if (rerunData) return;
      const templateIdToCreate = templateData?.id;
      if (
        templateData?.dataset_items &&
        templateIdToCreate &&
        datasetCreationRef.current !== templateIdToCreate
      ) {
        datasetCreationRef.current = templateIdToCreate;
        setIsPreparingDataset(true);

        try {
          const dataset = await getOrCreateDataset(
            templateData,
            workspaceName,
            project?.name,
          );
          if (dataset?.id) {
            form.setValue("datasetId", dataset.id, { shouldValidate: true });
          }
        } finally {
          setIsPreparingDataset(false);
        }
      }
    };

    internalCreateOrGetDataset();
  }, [
    rerunData,
    templateData,
    getOrCreateDataset,
    form,
    workspaceName,
    project?.name,
  ]);

  const isLoading = (Boolean(rerunId) && isRerunFetching) || isPreparingDataset;

  return (
    <ResizableSidePanel
      panelId="new-optimization-run-sidebar"
      entity="optimization run"
      open
      onClose={onClose}
      initialWidth={0.7}
      minWidth={640}
      blockOverlayClose
      header={
        <ResizableSidePanelTopBar
          variant="form"
          title={
            <span className="comet-body-s-accented">New optimization run</span>
          }
          onClose={onClose}
        />
      }
    >
      {isLoading ? (
        <Loader
          message={
            isPreparingDataset
              ? "Preparing a dataset..."
              : "Loading optimization..."
          }
        />
      ) : (
        <FormProvider {...form}>
          <OptimizationsNewPageContent onCancel={onClose} />
        </FormProvider>
      )}
    </ResizableSidePanel>
  );
};

export default NewRunSidebar;
