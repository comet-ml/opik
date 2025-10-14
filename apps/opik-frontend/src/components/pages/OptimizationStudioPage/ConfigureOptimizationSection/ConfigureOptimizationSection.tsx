import React from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";
import OptimizationRunHeader from "./OptimizationRunHeader";
import OptimizationResults from "./OptimizationResults";
import OptimizationLogs from "./OptimizationLogs";

const ConfigureOptimizationSection: React.FC = () => {
  const { activeOptimization } = useOptimizationStudioContext();

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div className="rounded-md border p-6">
        <h2 className="comet-title-m mb-6">Configure optimization</h2>

        {/* Configuration form */}
        <div className="space-y-4">
          <div className="flex items-end gap-2">
            <div className="flex-1">
              <Label htmlFor="dataset" className="comet-body-s mb-2 block">
                Dataset
              </Label>
              <Input
                id="dataset"
                placeholder=""
                disabled={Boolean(activeOptimization)}
              />
            </div>
            <Button variant="outline" size="sm" className="h-9">
              View DS
            </Button>
          </div>

          <div>
            <Label htmlFor="metric" className="comet-body-s mb-2 block">
              Metric
            </Label>
            <Input
              id="metric"
              placeholder="Built metrics"
              disabled={Boolean(activeOptimization)}
            />
          </div>

          <div>
            <Label htmlFor="algorithm" className="comet-body-s mb-2 block">
              Algorithm
            </Label>
            <Input
              id="algorithm"
              placeholder="<default value>"
              disabled={Boolean(activeOptimization)}
            />
          </div>

          <div>
            <Label htmlFor="trials" className="comet-body-s mb-2 block">
              Number of trials
            </Label>
            <Input
              id="trials"
              placeholder="<default value>"
              disabled={Boolean(activeOptimization)}
            />
          </div>
        </div>
      </div>

      {/* Optimization run section */}
      <OptimizationRunHeader optimization={activeOptimization} />

      {/* Chart, Best Prompt, and Logs */}
      <OptimizationResults optimization={activeOptimization} />

      <OptimizationLogs optimization={activeOptimization} />
    </div>
  );
};

export default ConfigureOptimizationSection;
