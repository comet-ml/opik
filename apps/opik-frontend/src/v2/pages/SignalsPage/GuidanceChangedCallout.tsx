import React from "react";

type GuidanceChangedCalloutProps = {
  // Undefined hides the link (no permission to run, or a run can't start now).
  onRun?: () => void;
};

const GuidanceChangedCallout: React.FC<GuidanceChangedCalloutProps> = ({
  onRun,
}) => (
  <div className="comet-body-xs shrink-0 rounded-md border border-[var(--chart-orange)] bg-[#FEE8D7] p-3 text-foreground-secondary dark:bg-[#FB934126]">
    Guidance changed since the last diagnostic run and results might be
    outdated.
    {onRun && (
      <>
        {" "}
        <button
          type="button"
          onClick={onRun}
          className="underline underline-offset-2 hover:opacity-80"
        >
          Run a new diagnostic
        </button>
      </>
    )}
  </div>
);

export default GuidanceChangedCallout;
