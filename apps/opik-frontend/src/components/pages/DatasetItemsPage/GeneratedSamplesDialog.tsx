import React, { useCallback, useState, useMemo, useEffect } from "react";
import { ChevronDown, ChevronRight, BarChart3, Eye } from "lucide-react";

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
import { DatasetItem } from "@/types/datasets";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { AlertTriangle } from "lucide-react";

type GeneratedSamplesDialogProps = {
  samples: DatasetItem[];
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddItems?: (items: DatasetItem[]) => void;
};

const GeneratedSamplesDialog: React.FunctionComponent<
  GeneratedSamplesDialogProps
> = ({ samples, open, setOpen, onAddItems }) => {
  const [selectedSamples, setSelectedSamples] = useState<Set<string>>(
    new Set(samples.map((sample) => sample.id)),
  );
  const [showAllSamples, setShowAllSamples] = useState(false);
  const [expandedSamples, setExpandedSamples] = useState<Set<string>>(
    new Set(),
  );

  // Update selectedSamples when samples prop changes (ensure all samples are selected by default)
  useEffect(() => {
    setSelectedSamples(new Set(samples.map((sample) => sample.id)));
  }, [samples]);

  // Analyze generated samples for statistics
  const sampleStats = useMemo(() => {
    if (!samples.length) return null;

    const fields = new Set<string>();
    const fieldValues: Record<string, Set<string>> = {};
    let generationModel: string | null = null;

    samples.forEach((sample) => {
      Object.entries(sample.data).forEach(([key, value]) => {
        // Skip metadata fields from diversity calculation
        if (key.startsWith("_")) {
          if (key === "_generation_model" && !generationModel) {
            generationModel = String(value);
          }
          return;
        }

        fields.add(key);
        if (!fieldValues[key]) fieldValues[key] = new Set();

        // Better value normalization for diversity
        let normalizedValue = String(value);
        if (typeof value === "string" && value.length > 50) {
          // For long strings, use first 50 chars to detect patterns
          normalizedValue = value.substring(0, 50);
        } else if (typeof value === "number") {
          // For numbers, round to 2 decimals to group similar values
          normalizedValue = Number(value).toFixed(2);
        }
        fieldValues[key].add(normalizedValue);
      });
    });

    // Calculate better diversity metrics
    const fieldDiversity = Object.fromEntries(
      Object.entries(fieldValues).map(([field, values]) => {
        const uniqueRatio = values.size / samples.length;
        let diversityScore = "Low";

        if (uniqueRatio > 0.8) diversityScore = "High";
        else if (uniqueRatio > 0.5) diversityScore = "Medium";

        return [
          field,
          {
            unique: values.size,
            total: samples.length,
            score: diversityScore,
            ratio: uniqueRatio,
          },
        ];
      }),
    );

    // Overall diversity score
    const avgDiversity =
      Object.values(fieldDiversity).reduce(
        (sum, field) => sum + field.ratio,
        0,
      ) / Object.keys(fieldDiversity).length;

    return {
      totalSamples: samples.length,
      totalFields: fields.size,
      fieldDiversity,
      overallDiversity: Math.round(avgDiversity * 100),
      generationModel: generationModel || "Unknown",
    };
  }, [samples]);

  // Smart sampling - show representative samples
  const displaySamples = useMemo(() => {
    if (showAllSamples) return samples;

    // For large sets, show first 15 samples as representative
    if (samples.length <= 20) return samples;
    return samples.slice(0, 15);
  }, [samples, showAllSamples]);

  // Get preview text for a sample
  const getSamplePreview = useCallback((sample: DatasetItem) => {
    const data = sample.data as Record<string, unknown>;
    const keys = Object.keys(data);
    const previewFields = keys.slice(0, 3);

    const preview = previewFields
      .map((key) => {
        const value = data[key];
        const displayValue =
          typeof value === "string"
            ? `"${value.length > 30 ? value.substring(0, 30) + "..." : value}"`
            : String(value);
        return `${key}: ${displayValue}`;
      })
      .join(", ");

    const remaining = keys.length - previewFields.length;
    return remaining > 0 ? `${preview}, +${remaining} more` : preview;
  }, []);

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

  const toggleSampleExpansion = useCallback((sampleId: string) => {
    setExpandedSamples((prev) => {
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
      selectedSamples.has(sample.id),
    );

    if (selectedItems.length === 0) return;

    onAddItems?.(selectedItems);
    setOpen(false);
  }, [samples, selectedSamples, onAddItems, setOpen]);

  const allSelected = selectedSamples.size === samples.length;
  const noneSelected = selectedSamples.size === 0;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="flex max-h-[85vh] max-w-3xl flex-col">
        <DialogHeader>
          <DialogTitle className="comet-title-s">
            Generated samples ({samples.length})
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="flex-1">
          <div className="space-y-4">
            {/* Empty Samples State */}
            {!samples.length && (
              <Alert variant="callout" size="sm">
                <AlertTriangle className="size-4" />
                <AlertTitle>No samples to display</AlertTitle>
                <AlertDescription>
                  No samples were generated. This might indicate an issue with
                  the generation process. Please try generating samples again.
                </AlertDescription>
              </Alert>
            )}

            {/* Statistics Summary */}
            {sampleStats && samples.length > 0 && (
              <div className="rounded-lg border bg-muted/20 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <BarChart3 className="size-4" />
                    <h4 className="text-sm font-medium">Generation Summary</h4>
                  </div>
                  <Tag variant="blue" size="sm">
                    Generated by {sampleStats.generationModel}
                  </Tag>
                </div>
                <div className="grid grid-cols-3 gap-4 text-sm">
                  <div>
                    <div className="text-muted-foreground">Total Samples</div>
                    <div className="font-medium">
                      {sampleStats.totalSamples}
                    </div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">
                      Fields per Sample
                    </div>
                    <div className="font-medium">{sampleStats.totalFields}</div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">Data Diversity</div>
                    <div className="flex items-center gap-2">
                      <span className="font-medium">
                        {sampleStats.overallDiversity}%
                      </span>
                      <Tag
                        variant={
                          sampleStats.overallDiversity > 80
                            ? "green"
                            : sampleStats.overallDiversity > 50
                              ? "yellow"
                              : "gray"
                        }
                        size="sm"
                      >
                        {sampleStats.overallDiversity > 80
                          ? "High"
                          : sampleStats.overallDiversity > 50
                            ? "Medium"
                            : "Low"}
                      </Tag>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Selection Controls */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Checkbox
                  checked={allSelected}
                  onCheckedChange={handleSelectAll}
                />
                <span className="text-sm">
                  {allSelected ? "Deselect all" : "Select all"}
                </span>
                {samples.length > 20 && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setShowAllSamples(!showAllSamples)}
                    className="ml-4"
                  >
                    <Eye className="mr-1 size-4" />
                    {showAllSamples
                      ? `Show sample (${displaySamples.length})`
                      : `Show all (${samples.length})`}
                  </Button>
                )}
              </div>
              <Tag variant="gray">
                {selectedSamples.size} of {samples.length} selected
              </Tag>
            </div>

            {/* Compact Sample List */}
            <div className="space-y-2">
              {displaySamples.map((sample, index) => {
                const isSelected = selectedSamples.has(sample.id);
                const isExpanded = expandedSamples.has(sample.id);
                const sampleNumber = showAllSamples
                  ? samples.indexOf(sample) + 1
                  : index + 1;

                return (
                  <div
                    key={sample.id}
                    className={`rounded-lg border transition-colors ${
                      isSelected
                        ? "border-primary bg-primary/5"
                        : "border-border hover:border-primary/50"
                    }`}
                  >
                    {/* Compact Header */}
                    <div className="flex items-center gap-3 p-3">
                      <Checkbox
                        checked={isSelected}
                        onCheckedChange={() => handleSelectSample(sample.id)}
                        onClick={(e) => e.stopPropagation()}
                      />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium">
                            Sample {sampleNumber}
                          </span>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => toggleSampleExpansion(sample.id)}
                            className="h-6 px-2"
                          >
                            {isExpanded ? (
                              <ChevronDown className="size-3" />
                            ) : (
                              <ChevronRight className="size-3" />
                            )}
                          </Button>
                        </div>
                        <div className="truncate text-xs text-muted-foreground">
                          {getSamplePreview(sample)}
                        </div>
                      </div>
                    </div>

                    {/* Expanded Content */}
                    {isExpanded && (
                      <div className="border-t bg-muted/10 p-3">
                        <SyntaxHighlighter data={sample.data} />
                      </div>
                    )}
                  </div>
                );
              })}

              {!showAllSamples && samples.length > displaySamples.length && (
                <div className="py-2 text-center">
                  <Button
                    variant="outline"
                    onClick={() => setShowAllSamples(true)}
                  >
                    Show {samples.length - displaySamples.length} more samples
                  </Button>
                </div>
              )}
            </div>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter className="gap-2 sm:space-x-0">
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button onClick={handleAddToDataset} disabled={noneSelected}>
            Add {selectedSamples.size} Sample
            {selectedSamples.size !== 1 ? "s" : ""} to Dataset
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default GeneratedSamplesDialog;
