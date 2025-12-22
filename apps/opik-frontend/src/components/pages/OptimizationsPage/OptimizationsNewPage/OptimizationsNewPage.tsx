import React, { useEffect, useMemo, useRef, useState } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryParam, StringParam } from "use-query-params";
import useAppStore from "@/store/AppStore";
import useGetOrCreateDemoDataset from "@/api/datasets/useGetOrCreateDemoDataset";
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
  const { getOrCreateDataset } = useGetOrCreateDemoDataset();
  const datasetCreationRef = useRef<string | null>(null);
  const [isPreparingDataset, setIsPreparingDataset] = useState(false);

  const templateData = useMemo(() => {
    if (templateId) {
      return (
        OPTIMIZATION_DEMO_TEMPLATES.find((t) => t.id === templateId) || null
      );
    }
    return null;
  }, [templateId]);

  const defaultValues = useMemo(() => {
    return convertOptimizationStudioToFormData(templateData);
  }, [templateData]);

  const form = useForm<OptimizationConfigFormType>({
    resolver: zodResolver(OptimizationConfigSchema),
    defaultValues,
    mode: "onChange",
  });

  // Reset form when template changes
  useEffect(() => {
    form.reset(convertOptimizationStudioToFormData(templateData));
    datasetCreationRef.current = null;
  }, [templateData, form]);

  // Create demo dataset when template with dataset_items is selected
  useEffect(() => {
    const internalCreateOrGetDataset = async () => {
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
  }, [templateData, getOrCreateDataset, form, workspaceName]);

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
