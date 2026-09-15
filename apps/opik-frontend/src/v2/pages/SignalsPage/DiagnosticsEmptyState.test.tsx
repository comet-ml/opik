import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

vi.mock("@/contexts/theme-provider", () => ({
  useTheme: () => ({ themeMode: "light" }),
}));

vi.mock("@/store/PluginsStore", () => ({
  default: () => undefined,
}));

import DiagnosticsEmptyState from "./DiagnosticsEmptyState";

const props = {
  awaitsAutoFirstRun: false,
  traceCount: 0,
  isOutOfCredits: false,
  canConfigure: true,
  onRun: vi.fn(),
  isRunPending: false,
};

describe("DiagnosticsEmptyState", () => {
  describe("awaiting the automatic first run", () => {
    const awaiting = { ...props, awaitsAutoFirstRun: true };

    it("reports progress toward the threshold", () => {
      render(<DiagnosticsEmptyState {...awaiting} traceCount={20} />);

      expect(screen.getByText("20/100")).toBeInTheDocument();
      expect(screen.getByText("Traces in the last 7 days")).toBeInTheDocument();
      expect(screen.getByRole("progressbar")).toHaveAttribute(
        "aria-valuenow",
        "20",
      );
    });

    it("fills the bar in proportion below the threshold", () => {
      render(<DiagnosticsEmptyState {...awaiting} traceCount={20} />);

      expect(screen.getByRole("progressbar").firstElementChild).toHaveStyle({
        width: "20%",
      });
    });

    it("caps the bar and the counter once the count passes the threshold", () => {
      render(<DiagnosticsEmptyState {...awaiting} traceCount={250} />);

      expect(screen.getByText("100/100")).toBeInTheDocument();
      expect(screen.getByRole("progressbar").firstElementChild).toHaveStyle({
        width: "100%",
      });
    });

    it("offers no run button, since the run is coming automatically", () => {
      render(<DiagnosticsEmptyState {...awaiting} traceCount={20} />);

      expect(
        screen.queryByRole("button", { name: /Run your first diagnostic/ }),
      ).not.toBeInTheDocument();
    });
  });

  describe("not enrolled", () => {
    it("offers a run button instead of progress or docs", () => {
      const onRun = vi.fn();
      render(<DiagnosticsEmptyState {...props} onRun={onRun} />);

      expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: /Read docs/ }),
      ).not.toBeInTheDocument();

      fireEvent.click(
        screen.getByRole("button", { name: /Run your first diagnostic/ }),
      );
      expect(onRun).toHaveBeenCalled();
    });

    it("asks for credits instead of a run when the organization has none", () => {
      render(<DiagnosticsEmptyState {...props} isOutOfCredits={true} />);

      expect(
        screen.getByRole("button", {
          name: /Add Ollie credits to run diagnostic/,
        }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /Run your first diagnostic/ }),
      ).not.toBeInTheDocument();
    });

    it("shows no call to action without configure permission", () => {
      render(<DiagnosticsEmptyState {...props} canConfigure={false} />);

      expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });
  });
});
