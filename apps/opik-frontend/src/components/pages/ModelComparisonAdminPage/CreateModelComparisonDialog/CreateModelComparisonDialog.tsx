import React, { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { X } from "lucide-react";
import { useCreateModelComparison } from "@/api/model-comparisons/useModelComparison";
import { useAvailableModels, useAvailableDatasets } from "@/api/model-comparisons/useModelComparison";

const createComparisonSchema = z.object({
  name: z.string().min(1, "Name is required"),
  description: z.string().optional(),
  modelIds: z.array(z.string()).min(2, "At least 2 models are required"),
  datasetNames: z.array(z.string()).min(1, "At least 1 dataset is required"),
});

type CreateComparisonFormData = z.infer<typeof createComparisonSchema>;

interface CreateModelComparisonDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const CreateModelComparisonDialog: React.FunctionComponent<CreateModelComparisonDialogProps> = ({
  open,
  onOpenChange,
}) => {
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [selectedDatasets, setSelectedDatasets] = useState<string[]>([]);

  const createMutation = useCreateModelComparison();
  const { data: availableModels = [] } = useAvailableModels();
  const { data: availableDatasets = [] } = useAvailableDatasets();

  const form = useForm<CreateComparisonFormData>({
    resolver: zodResolver(createComparisonSchema),
    defaultValues: {
      name: "",
      description: "",
      modelIds: [],
      datasetNames: [],
    },
  });

  const handleSubmit = (data: CreateComparisonFormData) => {
    const formData = {
      ...data,
      modelIds: selectedModels,
      datasetNames: selectedDatasets,
    };

    createMutation.mutate(formData, {
      onSuccess: () => {
        onOpenChange(false);
        form.reset();
        setSelectedModels([]);
        setSelectedDatasets([]);
      },
    });
  };

  const handleModelSelect = (modelName: string) => {
    if (!selectedModels.includes(modelName)) {
      setSelectedModels([...selectedModels, modelName]);
    }
  };

  const handleModelRemove = (modelName: string) => {
    setSelectedModels(selectedModels.filter(m => m !== modelName));
  };

  const handleDatasetSelect = (datasetName: string) => {
    if (!selectedDatasets.includes(datasetName)) {
      setSelectedDatasets([...selectedDatasets, datasetName]);
    }
  };

  const handleDatasetRemove = (datasetName: string) => {
    setSelectedDatasets(selectedDatasets.filter(d => d !== datasetName));
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Create Model Comparison</DialogTitle>
          <DialogDescription>
            Compare multiple LLM models across different datasets and metrics
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={form.handleSubmit(handleSubmit)} className="space-y-6">
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="name">Name *</Label>
              <Input
                id="name"
                {...form.register("name")}
                placeholder="e.g., GPT-4 vs Claude-3 Comparison"
              />
              {form.formState.errors.name && (
                <p className="comet-body-xs text-red-600">
                  {form.formState.errors.name.message}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="description">Description</Label>
              <Textarea
                id="description"
                {...form.register("description")}
                placeholder="Describe the purpose of this comparison..."
                rows={3}
              />
            </div>

            <div className="space-y-2">
              <Label>Models to Compare *</Label>
              <div className="space-y-2">
                <Select onValueChange={handleModelSelect}>
                  <SelectTrigger>
                    <SelectValue placeholder="Select models to compare" />
                  </SelectTrigger>
                  <SelectContent>
                    {availableModels.map((model) => (
                      <SelectItem 
                        key={model.name} 
                        value={model.name}
                        disabled={selectedModels.includes(model.name)}
                      >
                        <div className="flex items-center justify-between w-full">
                          <span>{model.name}</span>
                          <span className="text-xs text-muted-foreground ml-2">
                            ({model.provider})
                          </span>
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                
                {selectedModels.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {selectedModels.map((model) => (
                      <Badge key={model} variant="secondary" className="flex items-center gap-1">
                        {model}
                        <button
                          type="button"
                          onClick={() => handleModelRemove(model)}
                          className="ml-1 hover:bg-muted-foreground/20 rounded-full p-0.5"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </Badge>
                    ))}
                  </div>
                )}
                
                {selectedModels.length < 2 && (
                  <p className="comet-body-xs text-muted-foreground">
                    Select at least 2 models to compare
                  </p>
                )}
              </div>
            </div>

            <div className="space-y-2">
              <Label>Datasets *</Label>
              <div className="space-y-2">
                <Select onValueChange={handleDatasetSelect}>
                  <SelectTrigger>
                    <SelectValue placeholder="Select datasets to analyze" />
                  </SelectTrigger>
                  <SelectContent>
                    {availableDatasets.map((dataset) => (
                      <SelectItem 
                        key={dataset.name} 
                        value={dataset.name}
                        disabled={selectedDatasets.includes(dataset.name)}
                      >
                        <div className="flex items-center justify-between w-full">
                          <span>{dataset.name}</span>
                          <span className="text-xs text-muted-foreground ml-2">
                            ({dataset.experiment_count} experiments)
                          </span>
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                
                {selectedDatasets.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {selectedDatasets.map((dataset) => (
                      <Badge key={dataset} variant="outline" className="flex items-center gap-1">
                        {dataset}
                        <button
                          type="button"
                          onClick={() => handleDatasetRemove(dataset)}
                          className="ml-1 hover:bg-muted-foreground/20 rounded-full p-0.5"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </Badge>
                    ))}
                  </div>
                )}
                
                {selectedDatasets.length === 0 && (
                  <p className="comet-body-xs text-muted-foreground">
                    Select at least 1 dataset to analyze
                  </p>
                )}
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={createMutation.isPending || selectedModels.length < 2 || selectedDatasets.length === 0}
            >
              {createMutation.isPending ? "Creating..." : "Create Comparison"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
};

export default CreateModelComparisonDialog;