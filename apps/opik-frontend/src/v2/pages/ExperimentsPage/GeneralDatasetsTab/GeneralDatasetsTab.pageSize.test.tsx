import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  QueryParamProvider,
  NumberParam,
  useQueryParam,
} from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { ReactNode } from "react";

import {
  UIConfigProvider,
  useUIConfigValue,
} from "@/contexts/ui-config-provider";

const mockUseUIConfig = vi.fn();

vi.mock("@/api/ui-config/useUIConfig", () => ({
  default: () => mockUseUIConfig(),
}));

// Mirrors the inline pattern used by v2 Experiments tables (e.g.
// GeneralDatasetsTab, PromptPage/ExperimentsTab, useExperimentItemsState):
//   const { default_page_size: defaultPageSize } = useUIConfigValue();
//   const [sizeParam] = useQueryParam("size", NumberParam);
//   const size = sizeParam ?? defaultPageSize;
// The provider default is used only when the URL does not carry ?size.
function SizeProbe() {
  const { default_page_size: defaultPageSize } = useUIConfigValue();
  const [sizeParam] = useQueryParam("size", NumberParam);
  const size = sizeParam ?? defaultPageSize;
  return <span data-testid="size">{size}</span>;
}

function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <QueryParamProvider adapter={WindowHistoryAdapter}>
        <UIConfigProvider>{ui}</UIConfigProvider>
      </QueryParamProvider>
    </QueryClientProvider>,
  );
}

describe("v2 Experiments table page-size override", () => {
  const originalHref = window.location.href;

  beforeEach(() => {
    mockUseUIConfig.mockReset();
  });

  afterEach(() => {
    window.history.replaceState(null, "", originalHref);
  });

  it("uses the provider default_page_size when the URL has no ?size param", async () => {
    window.history.replaceState(null, "", "/experiments");
    mockUseUIConfig.mockReturnValue({ data: { default_page_size: 25 } });

    renderWithProviders(<SizeProbe />);

    await waitFor(() => {
      expect(screen.getByTestId("size").textContent).toBe("25");
    });
  });

  it("prefers the ?size URL param over the provider default_page_size", async () => {
    window.history.replaceState(null, "", "/experiments?size=50");
    mockUseUIConfig.mockReturnValue({ data: { default_page_size: 25 } });

    renderWithProviders(<SizeProbe />);

    await waitFor(() => {
      expect(screen.getByTestId("size").textContent).toBe("50");
    });
  });
});
