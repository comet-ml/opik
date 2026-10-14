import React from "react";
import OutOfCreditsButton, {
  OUT_OF_CREDITS_RUN_DESCRIPTION,
} from "@/v2/pages/SignalsPage/OutOfCreditsButton";

type GuidanceChangedCalloutProps = {
  // Undefined hides the link (no permission to run, or a run can't start now).
  onRun?: () => void;
  // Replaces the link with "Out of Ollie credits" and its billing popover.
  outOfCredits?: boolean;
};

const GuidanceChangedCallout: React.FC<GuidanceChangedCalloutProps> = ({
  onRun,
  outOfCredits = false,
}) => (
  <div className="comet-body-xs shrink-0 rounded-md border border-[var(--chart-orange)] bg-[#FEE8D7] p-3 text-foreground-secondary dark:bg-[#FB934126]">
    Guidance changed since the last diagnostic run and results might be
    outdated.
    {outOfCredits ? (
      <>
        {" "}
        <OutOfCreditsButton
          asLink
          label="Out of Ollie credits"
          description={OUT_OF_CREDITS_RUN_DESCRIPTION}
        />
      </>
    ) : (
      onRun && (
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
      )
    )}
  </div>
);

export default GuidanceChangedCallout;
