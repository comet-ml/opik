import React from "react";

interface ProgressIndicatorProps {
  totalItems: number;
  processedCount: number;
  className?: string;
}

const ProgressIndicator: React.FunctionComponent<ProgressIndicatorProps> = ({
  totalItems,
  processedCount,
  className = "",
}) => {
  return (
    <div className={`flex items-center justify-between ${className}`}>
      <div className="comet-body-xs text-gray-500">Progress</div>
      <div className="flex items-center space-x-4 flex-1 mx-4">
        <div className="bg-secondary h-2 flex-1 rounded-full">
          <div
            className="bg-primary h-2 rounded-full transition-all duration-300"
            style={{ width: `${(processedCount / totalItems) * 100}%` }}
          />
        </div>
      </div>
      <span className="comet-body-xs text-gray-500">
        {processedCount}/{totalItems} completed
      </span>
    </div>
  );
};

export default ProgressIndicator;
