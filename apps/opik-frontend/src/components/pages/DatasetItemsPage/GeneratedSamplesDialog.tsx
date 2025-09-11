import React, { useCallback, useState } from "react";
import { CheckCircle, Circle, Bot } from "lucide-react";

import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
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
import { Checkbox } from "@/components/ui/checkbox";
import { Tag } from "@/components/ui/tag";
import Loader from "@/components/shared/Loader/Loader";
import { DatasetItem } from "@/types/datasets";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

type GeneratedSamplesDialogProps = {
  datasetId: string;
  datasetName: string;
  samples: DatasetItem[];
  open: boolean;
  setOpen: (open: boolean) => void;
  onSamplesAdded?: () => void;
};

const GeneratedSamplesDialog: React.FunctionComponent<
  GeneratedSamplesDialogProps
> = ({ datasetId, datasetName, samples, open, setOpen, onSamplesAdded }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [selectedSamples, setSelectedSamples] = useState<Set<string>>(
    new Set(samples.map((sample) => sample.id))
  );

  const { mutate, isPending } = useDatasetItemBatchMutation();

  const handleSelectAll = useCallback(() => {
    if (selectedSamples.size === samples.length) {
      setSelectedSamples(new Set());
    } else {
      setSelectedSamples(new Set(samples.map((sample) => sample.id)));
    }
  }, [samples, selectedSamples]);

  const handleSelectSample = useCallback((sampleId: string) => {
    setSelectedSamples((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(sampleId)) {
        newSet.delete(sampleId);
      } else {
        newSet.add(sampleId);
      }
      return newSet;
    });
  }, []);

  const handleAddToDataset = useCallback(() => {
    const selectedItems = samples.filter((sample) =>
      selectedSamples.has(sample.id)
    );

    if (selectedItems.length === 0) return;

    mutate(
      {
        workspaceName,
        datasetId,
        datasetItems: selectedItems,
      },
      {
        onSuccess: () => {
          onSamplesAdded?.();
          setOpen(false);
        },
      }
    );
  }, [datasetId, datasetName, samples, selectedSamples, mutate, onSamplesAdded, setOpen]);

  const allSelected = selectedSamples.size === samples.length;
  const noneSelected = selectedSamples.size === 0;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-4xl max-h-[80vh]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Bot className="size-4" />
            Generated Samples ({samples.length})
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Checkbox
                  checked={allSelected}
                  onCheckedChange={handleSelectAll}
                />
                <span className="text-sm">
                  {allSelected ? "Deselect all" : "Select all"}
                </span>
              </div>
              <Tag variant="gray">
                {selectedSamples.size} of {samples.length} selected
              </Tag>
            </div>

            <div className="space-y-3">
              {samples.map((sample) => {
                const isSelected = selectedSamples.has(sample.id);
                return (
                  <div
                    key={sample.id}
                    className={`border rounded-lg p-4 cursor-pointer transition-colors ${
                      isSelected
                        ? "border-primary bg-primary/5"
                        : "border-border hover:border-primary/50"
                    }`}
                    onClick={() => handleSelectSample(sample.id)}
                  >
                    <div className="flex items-start gap-3">
                      <div className="mt-1">
                        {isSelected ? (
                          <CheckCircle className="size-4 text-primary" />
                        ) : (
                          <Circle className="size-4 text-muted-foreground" />
                        )}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-medium mb-2">
                          Sample {samples.indexOf(sample) + 1}
                        </div>
                        <SyntaxHighlighter
                          data={sample.data}
                        />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter className="gap-2">
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            onClick={handleAddToDataset}
            disabled={isPending || noneSelected}
          >
            {isPending && <Loader className="mr-2 size-4" />}
            Add {selectedSamples.size} Sample{selectedSamples.size !== 1 ? "s" : ""} to Dataset
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default GeneratedSamplesDialog;