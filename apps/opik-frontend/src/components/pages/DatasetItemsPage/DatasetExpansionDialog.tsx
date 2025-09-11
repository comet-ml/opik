import React, { useCallback, useState, useMemo, useEffect } from "react";
import { Bot, AlertCircle, Sparkles } from "lucide-react";

import useDatasetExpansionMutation from "@/api/datasets/useDatasetExpansionMutation";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useAppStore from "@/store/AppStore";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Slider } from "@/components/ui/slider";
import { Input } from "@/components/ui/input";
import { Alert, AlertDescription } from "@/components/ui/alert";
import Loader from "@/components/shared/Loader/Loader";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import { DatasetExpansionRequest, DatasetItem } from "@/types/datasets";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { Tag } from "@/components/ui/tag";

type DatasetExpansionDialogProps = {
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
  onSamplesGenerated?: (samples: DatasetItem[]) => void;
};

const DatasetExpansionDialog: React.FunctionComponent<
  DatasetExpansionDialogProps
> = ({ datasetId, open, setOpen, onSamplesGenerated }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  
  const [selectedModel, setSelectedModel] = useState<PROVIDER_MODEL_TYPE | "">("gpt-4o");
  const [selectedProvider, setSelectedProvider] = useState<PROVIDER_TYPE | "">(PROVIDER_TYPE.OPEN_AI);
  const [sampleCount, setSampleCount] = useState<number>(5);
  const [variationInstructions, setVariationInstructions] = useState<string>("");

  const { mutate, isPending } = useDatasetExpansionMutation();
  
  // Fetch sample dataset items to analyze structure
  const { data: sampleData, isLoading: isAnalyzing } = useDatasetItemsList({
    datasetId,
    page: 1,
    size: 5, // Analyze first 5 items
  });

  // Analyze dataset structure from sample data
  const datasetAnalysis = useMemo(() => {
    if (!sampleData?.content?.length) return null;
    
    const fields = new Set<string>();
    const fieldTypes: Record<string, Set<string>> = {};
    
    sampleData.content.forEach(item => {
      Object.keys(item.data).forEach(key => {
        fields.add(key);
        if (!fieldTypes[key]) fieldTypes[key] = new Set();
        
        const value = (item.data as any)[key];
        if (typeof value === 'object' && value !== null) {
          fieldTypes[key].add('object');
        } else {
          fieldTypes[key].add(typeof value);
        }
      });
    });
    
    return {
      totalFields: fields.size,
      commonFields: Array.from(fields),
      fieldTypes: Object.fromEntries(
        Object.entries(fieldTypes).map(([field, types]) => [
          field, 
          Array.from(types)
        ])
      ),
      sampleCount: sampleData.content.length
    };
  }, [sampleData?.content]);

  const handleSubmit = useCallback(() => {
    if (!selectedModel) return;

    const requestData: DatasetExpansionRequest = {
      model: selectedModel,
      sample_count: sampleCount,
      preserve_fields: datasetAnalysis?.commonFields || undefined,
      variation_instructions: variationInstructions?.trim() || undefined,
    };

    mutate(
      { datasetId, ...requestData },
      {
        onSuccess: (response) => {
          onSamplesGenerated?.(response.generated_samples);
          setOpen(false);
          // Reset form
          setSelectedModel("gpt-4o");
          setSampleCount(5);
          setVariationInstructions("");
        },
      },
    );
  }, [datasetId, selectedModel, sampleCount, datasetAnalysis?.commonFields, variationInstructions, mutate, onSamplesGenerated, setOpen]);

  const handleModelChange = useCallback((model: PROVIDER_MODEL_TYPE, provider: PROVIDER_TYPE) => {
    setSelectedModel(model);
    setSelectedProvider(provider);
  }, []);

  const handleSliderChange = useCallback((value: number[]) => {
    setSampleCount(value[0]);
  }, []);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Bot className="size-4" />
            Expand Dataset with AI
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="flex flex-col gap-4">
          <Alert>
            <AlertCircle className="h-4 w-4" />
            <AlertDescription>
              This will generate synthetic samples based on your existing data patterns. 
              The generated samples will be available for review before adding to your dataset.
            </AlertDescription>
          </Alert>

          {/* Dataset Analysis Section */}
          {isAnalyzing ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader className="size-4" />
              Analyzing dataset structure...
            </div>
          ) : datasetAnalysis ? (
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <Sparkles className="size-4 text-primary" />
                <Label className="text-sm font-medium">Dataset Structure Analysis</Label>
              </div>
              <div className="rounded-md border bg-muted/50 p-3 space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">Analyzed {datasetAnalysis.sampleCount} samples</span>
                  <Tag variant="gray" size="sm">{datasetAnalysis.totalFields} fields detected</Tag>
                </div>
                <div className="flex flex-wrap gap-1">
                  {datasetAnalysis.commonFields.map((field) => (
                    <Tag key={field} variant="blue" size="sm">
                      {field}
                    </Tag>
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">
                  Generated samples will maintain this structure and field patterns automatically.
                </p>
              </div>
            </div>
          ) : null}

          <div className="space-y-2">
            <Label htmlFor="model">Model</Label>
            <PromptModelSelect
              value={selectedModel}
              workspaceName={workspaceName}
              onChange={handleModelChange}
              provider={selectedProvider}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="sample-count">
              Number of samples: <Tag variant="gray">{sampleCount}</Tag>
            </Label>
            <Slider
              id="sample-count"
              min={1}
              max={20}
              step={1}
              value={[sampleCount]}
              onValueChange={handleSliderChange}
              className="w-full"
            />
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>1</span>
              <span>20</span>
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="variation-instructions">
              Additional instructions (optional)
            </Label>
            <Textarea
              id="variation-instructions"
              value={variationInstructions}
              onChange={(e) => setVariationInstructions(e.target.value)}
              placeholder="e.g., Create variations that test edge cases, focus on different user personas, include edge cases"
              rows={3}
            />
          </div>
        </DialogAutoScrollBody>
        <DialogFooter className="gap-2">
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button onClick={handleSubmit} disabled={isPending || !selectedModel}>
            {isPending && <Loader className="mr-2 size-4" />}
            Generate Samples
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DatasetExpansionDialog;