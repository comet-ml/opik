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
        title: "Diagnostics run failed",
        description: "An LLM provider error interrupted the run — try again.",
      };
    default:
      return {
        title: "Diagnostics run failed",
        description:
          "Something went wrong while running diagnostics — try again.",
      };
  }
};
