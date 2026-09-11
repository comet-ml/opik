// Keep the reason codes in sync with ollie-assist RunFailureCode and the BE.
export type RunFailureCopy = {
  title: string;
  description: string;
};

export const getRunFailureCopy = (reason?: string): RunFailureCopy => {
  switch (reason) {
    case "out_of_credits":
      return {
        title: "Diagnostics couldn't run",
        description: "You're out of LLM credits. Add credits and try again.",
      };
    case "rate_limited":
      return {
        title: "Diagnostics couldn't run",
        description:
          "The LLM provider is rate-limiting requests — try again shortly.",
      };
    case "provider_error":
      return {
        title: "LLM provider error",
        description:
          "The LLM provider returned an error mid-run. This is usually temporary — try again.",
      };
    case "did_not_start":
      return {
        title: "Diagnostics couldn't start",
        description:
          "The run was never picked up (the service may have been restarting). Try again.",
      };
    case "permission_denied":
      return {
        title: "Diagnostics couldn't access this project",
        description:
          "Agent Insights is missing the workspace permission needed to read this project's data. Contact your workspace admin.",
      };
    case "internal_error":
      return {
        title: "Diagnostics run failed",
        description:
          "An unexpected error stopped the run. Try again; if it keeps failing, contact support.",
      };
    default:
      return {
        title: "Diagnostics run failed",
        description:
          "Something went wrong while running diagnostics — try again.",
      };
  }
};
