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
import { X } from "lucide-react";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import Loader from "@/shared/Loader/Loader";
import OptimizationsNewPageContent from "./OptimizationsNewPageContent";

type NewRunSidebarProps = {
  open: boolean;
  onClose: () => void;
  templateId?: string;
  rerunId?: string;
};

type NewRunSidebarFormProps = Omit<NewRunSidebarProps, "open">;

// The form and its provider/rerun queries live here so they only mount while the
// sidebar is open (see NewRunSidebar below): no background queries when closed,
// and the form re-seeds fresh — and the "wizard started" event fires — on each open.
const NewRunSidebarForm: React.FC<NewRunSidebarFormProps> = ({
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

  // What we seed the form from: a rerun's saved config, else the picked
  // template, else nothing (a blank run).
  const seedSource = useMemo(
    () => rerunData || templateData,
    [rerunData, templateData],
  );

  // The converter mints fresh message ids on every call. The form below uses
  // `values` (not `defaultValues`), so it re-seeds once the model resolves
  // after providers load — and re-seeding with new ids would remount the
  // message rows and drop focus mid-typing. Message identity depends only on
  // the seed source, so derive it once here and reuse it across that re-seed.
  const stableMessages = useMemo(
    () => convertOptimizationStudioToFormData(seedSource).messages,
    [seedSource],
  );

  // Everything except messages also tracks the resolved models, so the default
  // model is picked from what the workspace can actually run. Pass no models
  // until providers are ready, so we never seed a guessed/empty model.
  const defaultValues = useMemo<OptimizationConfigFormType>(
    () => ({
      ...convertOptimizationStudioToFormData(
        seedSource,
        providersReady ? availableModels : [],
      ),
      messages: stableMessages,
    }),
    [seedSource, providersReady, availableModels, stableMessages],
  );

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    // `values` (not `defaultValues`) re-seeds the form when the resolved model
    // lands; `keepDirtyValues` preserves a model the user already picked.
    values: defaultValues,
    resetOptions: { keepDirtyValues: true },
    mode: "onSubmit",
    reValidateMode: "onChange",
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
            form.setValue("datasetId", dataset.id, {
              shouldDirty: true,
            });
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

  const isRerunLoading = Boolean(rerunId) && isRerunFetching;

  if (isRerunLoading) {
    return <Loader message="Loading optimization..." />;
  }

  return (
    <FormProvider {...form}>
      <OptimizationsNewPageContent
        onCancel={onClose}
        isPreparingDataset={isPreparingDataset}
      />
    </FormProvider>
  );
};

// Thin, always-mounted shell. ResizableSidePanel is fully controlled and drives
// the slide-in/out from `open` via CSS, so the parent just toggles `open` (no
// mount-time flip or close timer needed). The form content mounts only while
// open, matching the panel's own `open`-gated rendering.
const NewRunSidebar: React.FC<NewRunSidebarProps> = ({
  open,
  onClose,
  templateId,
  rerunId,
}) => {
  return (
    <ResizableSidePanel
      panelId="new-optimization-run-sidebar"
      entity="optimization run"
      open={open}
      onClose={onClose}
      initialWidth={0.7}
      minWidth={640}
      header={
        // Custom header (not the shared ResizableSidePanelTopBar) to hit the
        // Figma spec: 16px left to the ✕ (the panel wrapper's pl-2 + this pl-2)
        // and 12px between the ✕ and the title.
        <div className="flex flex-auto items-center gap-3 pl-1">
          <TooltipWrapper content="Close panel">
            <Button variant="ghost" size="icon-2xs" onClick={onClose}>
              <X />
              <span className="sr-only">Close</span>
            </Button>
          </TooltipWrapper>
          <span className="comet-body-s-accented truncate">
            New optimization run
          </span>
        </div>
      }
    >
      {open && (
        <NewRunSidebarForm
          onClose={onClose}
          templateId={templateId}
          rerunId={rerunId}
        />
      )}
    </ResizableSidePanel>
  );
};

export default NewRunSidebar;
