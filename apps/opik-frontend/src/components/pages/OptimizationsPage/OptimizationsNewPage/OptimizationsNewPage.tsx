import React, { useEffect, useMemo, useRef, useState } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryParam, StringParam } from "use-query-params";
import useAppStore from "@/store/AppStore";
import useGetOrCreateDemoDataset from "@/api/datasets/useGetOrCreateDemoDataset";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import {
  OptimizationConfigFormType,
  OptimizationConfigSchema,
  convertOptimizationStudioToFormData,
} from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import { OPTIMIZATION_DEMO_TEMPLATES } from "@/constants/optimizations";
import OptimizationsNewPageContent from "./OptimizationsNewPageContent";
import Loader from "@/components/shared/Loader/Loader";

const OptimizationsNewPage: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [templateId] = useQueryParam("template", StringParam);
  const [rerunId] = useQueryParam("rerun", StringParam);
  const { getOrCreateDataset } = useGetOrCreateDemoDataset();
  const datasetCreationRef = useRef<string | null>(null);
  const [isPreparingDataset, setIsPreparingDataset] = useState(false);

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

  const defaultValues = useMemo(() => {
    return convertOptimizationStudioToFormData(rerunData || templateData);
  }, [rerunData, templateData]);

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    defaultValues,
    mode: "onChange",
  });

  // Reset form when template changes
  useEffect(() => {
    form.reset(convertOptimizationStudioToFormData(rerunData || templateData));
    datasetCreationRef.current = null;
  }, [rerunData, templateData, form]);

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
          const dataset = await getOrCreateDataset(templateData, workspaceName);
          if (dataset?.id) {
            form.setValue("datasetId", dataset.id, { shouldValidate: true });
          }
        } finally {
          setIsPreparingDataset(false);
        }
      }
    };

    internalCreateOrGetDataset();
  }, [rerunData, templateData, getOrCreateDataset, form, workspaceName]);

  if (Boolean(rerunId) && isRerunFetching) {
    return <Loader message="Loading optimization..." />;
  }

  if (isPreparingDataset) {
    return <Loader message="Preparing a dataset..." />;
  }

  return (
    <FormProvider {...form}>
      <OptimizationsNewPageContent />
    </FormProvider>
  );
};

export default OptimizationsNewPage;
