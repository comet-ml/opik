import React from "react";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { OPTIMIZATION_ALGORITHM } from "@/types/optimization-studio";

interface OptimizationConfigProps {
  datasetId: string;
  onDatasetChange: (datasetId: string) => void;
  algorithm: OPTIMIZATION_ALGORITHM;
  onAlgorithmChange: (algorithm: OPTIMIZATION_ALGORITHM) => void;
}

const OptimizationConfig: React.FC<OptimizationConfigProps> = ({
  datasetId,
  onDatasetChange,
  algorithm,
  onAlgorithmChange,
}) => {
  return (
    <div className="flex flex-col gap-4">
      <h2 className="comet-body-s-accented">Configure Optimization</h2>

      <div className="flex gap-4">
        <div className="flex flex-1 flex-col gap-2">
          <label className="comet-body-s text-muted-slate">Dataset</label>
          <DatasetSelectBox
            value={datasetId}
            onValueChange={onDatasetChange}
            placeholder="Select a dataset"
            className="w-full"
          />
        </div>

        <div className="flex flex-1 flex-col gap-2">
          <label className="comet-body-s text-muted-slate">Algorithm</label>
          <Select
            value={algorithm}
            onValueChange={(value) =>
              onAlgorithmChange(value as OPTIMIZATION_ALGORITHM)
            }
          >
            <SelectTrigger className="w-full">
              <SelectValue placeholder="Select an algorithm" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem
                value={OPTIMIZATION_ALGORITHM.EVOLUTIONARY}
                withoutCheck
              >
                Evolutionary
              </SelectItem>
              <SelectItem
                value={OPTIMIZATION_ALGORITHM.HIERARCHICAL_REFLECTIVE}
                withoutCheck
              >
                Hierarchical Reflective
              </SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>
  );
};

export default OptimizationConfig;
