import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import CopyEntityActions from "./CopyEntityActions";

const mockCopy = vi.hoisted(() => vi.fn());
vi.mock("clipboard-copy", () => ({ default: mockCopy }));

const mockToast = vi.hoisted(() => vi.fn());
vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
  toast: mockToast,
}));

const ENTITY_ID = "019ce65c-e695-7f93-b75b-68a4a9141f09";

const renderActions = (props = {}) =>
  render(
    <TooltipProvider>
      <CopyEntityActions entityId={ENTITY_ID} entityLabel="trace" {...props} />
    </TooltipProvider>,
  );

const iconOf = (button: HTMLElement) =>
  button.querySelector("svg")?.getAttribute("class") ?? "";

describe("CopyEntityActions", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockCopy.mockClear();
    mockToast.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders a copy-ID and a copy-link button", () => {
    renderActions();

    expect(screen.getAllByRole("button")).toHaveLength(2);
    expect(screen.getByLabelText("Copy trace ID")).toBeTruthy();
    expect(screen.getByLabelText("Copy trace link")).toBeTruthy();
  });

  it.each([
    ["trace", "Copy trace ID", "Copy trace link"],
    ["thread", "Copy thread ID", "Copy thread link"],
    ["span", "Copy span ID", "Copy span link"],
  ] as const)(
    "labels both buttons for entityLabel=%s",
    (entityLabel, idLabel, linkLabel) => {
      renderActions({ entityLabel });

      expect(screen.getByLabelText(idLabel)).toBeTruthy();
      expect(screen.getByLabelText(linkLabel)).toBeTruthy();
    },
  );

  it("copies the entity id when the copy-ID button is clicked", () => {
    renderActions();

    fireEvent.click(screen.getByLabelText("Copy trace ID"));

    expect(mockCopy).toHaveBeenCalledWith(ENTITY_ID);
  });

  it("copies the current url when the copy-link button is clicked", () => {
    renderActions();

    fireEvent.click(screen.getByLabelText("Copy trace link"));

    expect(mockCopy).toHaveBeenCalledWith(window.location.href);
  });

  it("omits the link button when withLink is false", () => {
    renderActions({ withLink: false });

    expect(screen.getByLabelText("Copy trace ID")).toBeTruthy();
    expect(screen.queryByLabelText("Copy trace link")).toBeNull();
    expect(screen.getAllByRole("button")).toHaveLength(1);
  });

  it("swaps only the clicked button to the check icon", () => {
    renderActions();
    const idButton = screen.getByLabelText("Copy trace ID");
    const linkButton = screen.getByLabelText("Copy trace link");

    fireEvent.click(idButton);

    expect(iconOf(idButton)).toContain("lucide-check");
    expect(iconOf(linkButton)).not.toContain("lucide-check");
    expect(iconOf(linkButton)).toContain("lucide-link");
  });

  it("relabels the button while the check is showing", () => {
    renderActions();

    fireEvent.click(screen.getByLabelText("Copy trace ID"));

    expect(screen.getByLabelText("Copied")).toBeTruthy();
    expect(screen.queryByLabelText("Copy trace ID")).toBeNull();

    act(() => {
      vi.advanceTimersByTime(3100);
    });

    expect(screen.getByLabelText("Copy trace ID")).toBeTruthy();
  });

  it("reverts the check icon after 3s", () => {
    renderActions();
    const idButton = screen.getByLabelText("Copy trace ID");

    fireEvent.click(idButton);
    act(() => {
      vi.advanceTimersByTime(2900);
    });
    expect(iconOf(idButton)).toContain("lucide-check");

    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(iconOf(idButton)).toContain("lucide-copy");
  });

  it("restarts the timer instead of stacking on repeat clicks", () => {
    renderActions();
    const idButton = screen.getByLabelText("Copy trace ID");

    fireEvent.click(idButton);
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    fireEvent.click(idButton);

    // 2s after the second click the original 3s deadline has passed,
    // but the restarted timer keeps the check visible.
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(iconOf(idButton)).toContain("lucide-check");

    act(() => {
      vi.advanceTimersByTime(1100);
    });
    expect(iconOf(idButton)).toContain("lucide-copy");
  });

  it("clears the pending revert timer on unmount", () => {
    const setTimeoutSpy = vi.spyOn(window, "setTimeout");
    const clearTimeoutSpy = vi.spyOn(window, "clearTimeout");
    const { unmount } = renderActions();

    fireEvent.click(screen.getByLabelText("Copy trace ID"));
    const timerId = setTimeoutSpy.mock.results.at(-1)?.value;
    clearTimeoutSpy.mockClear();

    unmount();

    expect(clearTimeoutSpy).toHaveBeenCalledWith(timerId);

    setTimeoutSpy.mockRestore();
    clearTimeoutSpy.mockRestore();
  });

  it("does not fire a toast when copying", () => {
    renderActions();

    fireEvent.click(screen.getByLabelText("Copy trace ID"));
    fireEvent.click(screen.getByLabelText("Copy trace link"));

    expect(mockToast).not.toHaveBeenCalled();
  });

  it("keeps both buttons in the tab order", () => {
    renderActions();

    expect(screen.getByLabelText("Copy trace ID").tabIndex).toBe(0);
    expect(screen.getByLabelText("Copy trace link").tabIndex).toBe(0);
  });
});
