import { lazy, Suspense } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { router } from "@/v1/router";
import { ThemeProvider } from "@/contexts/theme-provider";
import { Toaster } from "@/ui/toaster";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import useCustomScrollbarClass from "@/hooks/useCustomScrollbarClass";
import SentryErrorBoundary from "@/v1/layout/SentryErrorBoundary/SentryErrorBoundary";
import { TooltipProvider } from "@/ui/tooltip";
import { PostHogProvider } from "posthog-js/react";
import posthog from "posthog-js";

// Lazy-loaded — see v2/App.tsx for rationale. Same ~980 KB chunk issue.
const DatasetExportPanel = lazy(
  () =>
    import("@/v1/pages-shared/datasets/DatasetExportPanel/DatasetExportPanel"),
);

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
                <Suspense fallback={null}>
                  <DatasetExportPanel />
                </Suspense>
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
