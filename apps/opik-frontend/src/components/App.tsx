import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { router } from "@/router";
import { ThemeProvider } from "@/components/theme-provider";
import { Toaster } from "@/components/ui/toaster";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import useCustomScrollbarClass from "@/hooks/useCustomScrollbarClass";

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
    <QueryClientProvider client={queryClient}>
      <QueryParamProvider adapter={WindowHistoryAdapter}>
        <ThemeProvider defaultTheme="light" storageKey="vite-ui-theme">
          <RouterProvider router={router} />
          <Toaster />
        </ThemeProvider>
      </QueryParamProvider>
    </QueryClientProvider>
  );
}

export default App;
