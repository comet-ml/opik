import React from "react";

const OverallPerformanceSection = () => {
  return (
    <div className="pb-4 pt-6">
      <div className="sticky top-0 z-10 bg-soft-background pb-3 pt-2">
        <h2 className="comet-title-m truncate break-words">
          Overall performance
        </h2>
      </div>
      <div className="min-h-72">Metrics chart</div>
      <div className="min-h-72">Cost chart</div>
    </div>
  );
};

export default OverallPerformanceSection;
