import { Button } from "@/components/ui/button";
import { ChartLine } from "lucide-react";
import React from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import useProjectTabMetrics from "@/components/pages/TracesPage/MetricsTab/useProjectTabMetrics";

const TIME_OPTIONS = [
  {
    value: "1 day",
    label: "1 day",
  },
  {
    value: "3 days",
    label: "3 days",
  },
  {
    value: "7 days",
    label: "7 days",
  },
  {
    value: "30 days",
    label: "30 days",
  },
];

interface MetricsTabProps {
  projectId: string;
}

const MetricsTab = ({ projectId }: MetricsTabProps) => {
  useProjectTabMetrics({ projectId });

  return (
    <div>
      <div className="flex justify-between items-center">
        <Button variant="outline">
          <ChartLine className="mr-2 size-4" />
          Request a chart
        </Button>

        <div className="w-[160px]">
          <SelectBox
            value={TIME_OPTIONS[3].value}
            onChange={() => {}}
            options={TIME_OPTIONS}
          />
        </div>
      </div>

      <div
        className="flex md:flex-col gap-4 py-4"
        style={{ "--chart-height": "230px" } as React.CSSProperties}
      >
        <div className="flex gap-4 h-[var(--chart-height)]">
          <div className="flex-1 bg-white border border-gray-300 rounded-lg p-4">
            Number of traces
          </div>
          <div className="flex-1 bg-white border border-gray-300 rounded-lg p-4">
            Duration
          </div>
        </div>

        <div className="flex gap-4 h-[var(--chart-height)]">
          <div className="flex-1 bg-white border border-gray-300 rounded-lg p-4">
            Token usage
          </div>
          <div className="flex-1 bg-white border border-gray-300 rounded-lg p-4">
            Estimated cost
          </div>
        </div>
      </div>
    </div>
  );
};

export default MetricsTab;
