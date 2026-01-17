import React, { useCallback, useMemo } from "react";
import { useFormContext } from "react-hook-form";
import { Download } from "lucide-react";
import FileSaver from "file-saver";
import { Button } from "@/components/ui/button";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { OptimizationConfigFormType } from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import { generatePythonCode } from "@/lib/optimizations/generatePythonCode";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useAppStore from "@/store/AppStore";

type OptimizationsNewHeaderProps = {
  isSubmitting: boolean;
  isFormValid: boolean;
  onSubmit: () => void;
  onCancel: () => void;
};

const OptimizationsNewHeader: React.FC<OptimizationsNewHeaderProps> = ({
  isSubmitting,
  isFormValid,
  onSubmit,
  onCancel,
}) => {
  const form = useFormContext<OptimizationConfigFormType>();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: datasetsData } = useDatasetsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );

  const handleDownload = useCallback(() => {
    const formData = form.getValues();
    const selectedDataset = datasets.find((ds) => ds.id === formData.datasetId);
    const datasetName = selectedDataset?.name || "";

    if (!datasetName) {
      return;
    }

    try {
      const pythonCode = generatePythonCode(formData, datasetName);
      const blob = new Blob([pythonCode], { type: "text/x-python;charset=utf-8" });
      const fileName = `optimization_${formData.name || "prompt"}.py`.replace(
        /[^a-z0-9._-]/gi,
        "_",
      );
      FileSaver.saveAs(blob, fileName);
    } catch (error) {
      console.error("Failed to generate Python code:", error);
    }
  }, [form, datasets]);

  const isDownloadDisabled = isSubmitting || !isFormValid;

  return (
    <>
      <div className="mb-2 flex items-center justify-between">
        <h1 className="comet-title-l">Optimize a prompt</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={onSubmit}
            disabled={isSubmitting || !isFormValid}
          >
            {isSubmitting ? "Starting..." : "Optimize prompt"}
          </Button>
          <TooltipWrapper content="Download Python code">
            <Button
              variant="outline"
              size="sm"
              onClick={handleDownload}
              disabled={isDownloadDisabled}
            >
              <Download className="size-4" />
            </Button>
          </TooltipWrapper>
        </div>
      </div>
      <ExplainerDescription
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimization_config]}
        className="mb-6"
      />
    </>
  );
};

export default OptimizationsNewHeader;
