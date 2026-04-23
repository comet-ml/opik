import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";
import { UIConfigProvider, useUIConfigValue } from "./ui-config-provider";

const mockUseUIConfig = vi.fn();

vi.mock("@/api/ui-config/useUIConfig", () => ({
  default: () => mockUseUIConfig(),
}));

function Consumer() {
  const { default_page_size } = useUIConfigValue();
  return <span data-testid="size">{default_page_size}</span>;
}

function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <UIConfigProvider>{ui}</UIConfigProvider>
    </QueryClientProvider>,
  );
}

describe("UIConfigProvider", () => {
  beforeEach(() => {
    mockUseUIConfig.mockReset();
  });

  it("exposes the fetched default_page_size when the hook returns data", async () => {
    mockUseUIConfig.mockReturnValue({ data: { default_page_size: 25 } });

    renderWithProviders(<Consumer />);

    await waitFor(() => {
      expect(screen.getByTestId("size").textContent).toBe("25");
    });
  });

  it("falls back to 100 when the hook has no data (loading / error)", () => {
    mockUseUIConfig.mockReturnValue({ data: undefined });

    renderWithProviders(<Consumer />);

    expect(screen.getByTestId("size").textContent).toBe("100");
  });
});
