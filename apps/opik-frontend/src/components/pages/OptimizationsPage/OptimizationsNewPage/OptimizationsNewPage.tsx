import React, { useEffect, useMemo, useRef } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryParam, StringParam } from "use-query-params";
import useGetOrCreateDemoDataset from "@/api/datasets/useGetOrCreateDemoDataset";
import {
  OptimizationConfigFormType,
  OptimizationConfigSchema,
  convertOptimizationStudioToFormData,
} from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import { OPTIMIZATION_DEMO_TEMPLATES } from "@/constants/optimizations";
import OptimizationsNewPageContent from "./OptimizationsNewPageContent";

const OptimizationsNewPage: React.FC = () => {
  const [templateId] = useQueryParam("template", StringParam);
  const { getOrCreateDataset } = useGetOrCreateDemoDataset();
  const datasetCreationRef = useRef<string | null>(null);

  const templateData = useMemo(() => {
    if (templateId) {
      return OPTIMIZATION_DEMO_TEMPLATES.find((t) => t.id === templateId) || null;
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
    const templateIdToCreate = templateData?.id;
    if (
      templateData?.dataset_items &&
      templateIdToCreate &&
      datasetCreationRef.current !== templateIdToCreate
    ) {
      datasetCreationRef.current = templateIdToCreate;

      getOrCreateDataset(templateData).then((dataset) => {
        if (dataset?.id) {
          form.setValue("datasetId", dataset.id, { shouldValidate: true });
        }
      });
    }
  }, [templateData, getOrCreateDataset, form]);

  return (
    <FormProvider {...form}>
      <OptimizationsNewPageContent />
    </FormProvider>
  );
};

export default OptimizationsNewPage;
