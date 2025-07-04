import React from "react";
import { MetricPanelConfig } from "./dashboardTypes";

interface MetricPanelProps {
  config: MetricPanelConfig;
  id: string;
}

const MetricPanel: React.FC<MetricPanelProps> = ({ config, id }) => {
  const { metricName, aggregation, timeRange, displayFormat } = config;

  // Generate placeholder metric value
  const generatePlaceholderValue = () => {
    const baseValue = Math.random() * 100;
    switch (displayFormat) {
      case "percentage":
        return `${baseValue.toFixed(1)}%`;
      case "currency":
        return `$${baseValue.toFixed(2)}`;
      case "number":
      default:
        return baseValue.toFixed(2);
    }
  };

  const placeholderValue = generatePlaceholderValue();

  const getAggregationLabel = () => {
    switch (aggregation) {
      case "avg": return "Average";
      case "sum": return "Sum";
      case "count": return "Count";
      case "min": return "Minimum";
      case "max": return "Maximum";
      default: return aggregation;
    }
  };

  const getTimeRangeLabel = () => {
    switch (timeRange) {
      case "1h": return "Last Hour";
      case "24h": return "Last 24 Hours";
      case "7d": return "Last 7 Days";
      case "30d": return "Last 30 Days";
      case "all": return "All Time";
      default: return timeRange;
    }
  };

  return (
    <div className="h-full flex flex-col items-center justify-center bg-background p-4">
      <div className="text-center">
        <div className="text-4xl mb-3">📈</div>
        <h4 className="comet-title-s mb-3 text-foreground">
          {metricName || "Unnamed Metric"}
        </h4>
        
        <div className="text-4xl font-bold text-primary mb-4">
          {placeholderValue}
        </div>
        
        <div className="comet-body-s text-muted-foreground space-y-1">
          <p><strong>Aggregation:</strong> {getAggregationLabel()}</p>
          <p><strong>Time Range:</strong> {getTimeRangeLabel()}</p>
          <p><strong>Format:</strong> {displayFormat}</p>
        </div>
        
        <div className="mt-4 p-3 bg-accent/50 rounded-md border">
          <p className="comet-body-xs text-muted-foreground">
            Metric value is placeholder - will be calculated from real data
          </p>
        </div>
      </div>
    </div>
  );
};

export default MetricPanel; 