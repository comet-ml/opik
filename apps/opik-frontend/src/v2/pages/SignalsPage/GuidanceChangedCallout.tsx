import React from "react";
import usePluginsStore from "@/store/PluginsStore";

type GuidanceChangedCalloutProps = {
  // Undefined hides the link (no permission to run, or a run can't start now).
  onRun?: () => void;
  // Says there aren't enough Ollie credits for a new run, with a billing link for org admins.
  outOfCredits?: boolean;
};

const GuidanceChangedCallout: React.FC<GuidanceChangedCalloutProps> = ({
  onRun,
  outOfCredits = false,
}) => {
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  return (
    <div className="comet-body-xs shrink-0 rounded-md border border-[var(--chart-orange)] bg-guidance-callout-bg p-3 text-foreground-secondary">
      {/* Each sentence and link is its own element: browser translation re-parents bare text nodes. */}
      <span>
        Guidance changed since the last diagnostic run and results might be
        outdated.
      </span>
      {outOfCredits ? (
        <>
          <span>
            {" "}
            There aren&rsquo;t enough Ollie credits for a new diagnostic.
          </span>
          {BillingLink && (
            <span>
              {" "}
              <BillingLink label="View billing" />
            </span>
          )}
        </>
      ) : (
        onRun && (
          <span>
            {" "}
            <button
              type="button"
              onClick={onRun}
              className="underline underline-offset-2 hover:opacity-80"
            >
              Run a new diagnostic
            </button>
          </span>
        )
      )}
    </div>
  );
};

export default GuidanceChangedCallout;
