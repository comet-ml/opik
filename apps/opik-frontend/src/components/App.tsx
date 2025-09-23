import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { router } from "@/router";
import { ThemeProvider } from "@/components/theme-provider";
import { Toaster } from "@/components/ui/toaster";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import useCustomScrollbarClass from "@/hooks/useCustomScrollbarClass";
import SentryErrorBoundary from "@/components/layout/SentryErrorBoundary/SentryErrorBoundary";
import { TooltipProvider } from "@/components/ui/tooltip";
import { PostHogProvider } from "posthog-js/react";
import posthog from "posthog-js";

const TOOLTIP_DELAY_DURATION = 500;
const TOOLTIP_SKIP__DELAY_DURATION = 0;

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
    },
  },
});

function App() {
  useCustomScrollbarClass();

  return (
    <SentryErrorBoundary>
      <PostHogProvider client={posthog}>
        <QueryClientProvider client={queryClient}>
          <QueryParamProvider adapter={WindowHistoryAdapter}>
            <ThemeProvider>
              <TooltipProvider
                delayDuration={TOOLTIP_DELAY_DURATION}
                skipDelayDuration={TOOLTIP_SKIP__DELAY_DURATION}
              >
                <RouterProvider router={router} />
              </TooltipProvider>
              <Toaster />
            </ThemeProvider>
          </QueryParamProvider>
        </QueryClientProvider>
      </PostHogProvider>
    </SentryErrorBoundary>
  );
}

export default App;
