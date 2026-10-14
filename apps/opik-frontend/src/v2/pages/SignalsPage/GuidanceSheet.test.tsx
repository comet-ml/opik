import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { AGENT_INSIGHTS_JOB_STATUS, AgentInsightsJob } from "@/types/signals";

const mutate = vi.fn();

vi.mock("@/api/signals/useUpdateAgentInsightsGuidanceMutation", () => ({
  default: () => ({ mutate, isPending: false }),
}));

import GuidanceSheet from "./GuidanceSheet";

const job: AgentInsightsJob = {
  id: "j1",
  project_id: "p1",
  status: AGENT_INSIGHTS_JOB_STATUS.disabled,
  guidance: "Retries are expected.",
  guidance_updated_by: "Jolanta",
  guidance_updated_at: "2026-10-02T10:00:00Z",
  guidance_version: 1,
};

const renderSheet = (props: Partial<Parameters<typeof GuidanceSheet>[0]>) =>
  render(
    <GuidanceSheet
      open
      setOpen={vi.fn()}
      projectId="p1"
      job={job}
      onRun={vi.fn()}
      {...props}
    />,
  );

const openSaveMenu = () =>
  fireEvent.keyDown(screen.getByRole("button", { name: "Show more options" }), {
    key: "Enter",
  });

describe("GuidanceSheet", () => {
  beforeEach(() => {
    mutate.mockReset();
    mutate.mockImplementation((_vars, options) => options?.onSuccess?.());
  });

  it("starts from the saved guidance and says who saved it", () => {
    renderSheet({});

    expect(screen.getByLabelText("About this project")).toHaveValue(
      "Retries are expected.",
    );
    expect(screen.getByText("Saved Oct 2 by Jolanta")).toBeInTheDocument();
  });

  it("saves, then runs a diagnostic from the primary button", () => {
    const setOpen = vi.fn();
    const onRun = vi.fn();
    renderSheet({ setOpen, onRun });

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "Only report tool failures." },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Save and run diagnostic" }),
    );

    expect(mutate).toHaveBeenCalledWith(
      { projectId: "p1", guidance: "Only report tool failures." },
      expect.anything(),
    );
    expect(setOpen).toHaveBeenCalledWith(false);
    expect(onRun).toHaveBeenCalledTimes(1);
  });

  it("saves without running from the split menu", () => {
    const onRun = vi.fn();
    renderSheet({ onRun });

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "" },
    });
    openSaveMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Save guidance" }));

    expect(mutate).toHaveBeenCalledWith(
      { projectId: "p1", guidance: "" },
      expect.anything(),
    );
    expect(onRun).not.toHaveBeenCalled();
  });

  it("disables saving until the guidance changes", () => {
    renderSheet({});

    expect(
      screen.getByRole("button", { name: "Save and run diagnostic" }),
    ).toBeDisabled();

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "Draft" },
    });

    expect(
      screen.getByRole("button", { name: "Save and run diagnostic" }),
    ).toBeEnabled();
  });

  it("closes on Esc when nothing changed", () => {
    const setOpen = vi.fn();
    renderSheet({ setOpen });

    fireEvent.keyDown(screen.getByLabelText("About this project"), {
      key: "Escape",
    });

    expect(setOpen).toHaveBeenCalledWith(false);
  });

  it("asks before discarding unsaved edits", () => {
    const setOpen = vi.fn();
    renderSheet({ setOpen });

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "Draft" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.getByText("Discard changes?")).toBeInTheDocument();
    expect(setOpen).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
    expect(setOpen).not.toHaveBeenCalled();
    expect(screen.getByLabelText("About this project")).toHaveValue("Draft");

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));
    expect(setOpen).toHaveBeenCalledWith(false);
  });

  it("asks before discarding when clicking outside with unsaved edits", async () => {
    const setOpen = vi.fn();
    renderSheet({ setOpen });

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "Draft" },
    });
    // Radix registers its outside-pointer listener on the next tick.
    await new Promise((r) => setTimeout(r));
    fireEvent.pointerDown(document.body);

    expect(await screen.findByText("Discard changes?")).toBeInTheDocument();
    expect(setOpen).not.toHaveBeenCalled();
  });

  it("doesn't wipe typing when the saved guidance refetches while open", () => {
    const { rerender } = renderSheet({});

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "Draft" },
    });
    rerender(
      <GuidanceSheet
        open
        setOpen={vi.fn()}
        projectId="p1"
        job={{ ...job, guidance: "Changed elsewhere" }}
        onRun={vi.fn()}
      />,
    );

    expect(screen.getByLabelText("About this project")).toHaveValue("Draft");
  });

  it("offers only Save when a run can't start", () => {
    const setOpen = vi.fn();
    renderSheet({ setOpen, onRun: undefined });

    expect(
      screen.queryByRole("button", { name: "Save and run diagnostic" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Show more options" }),
    ).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("About this project"), {
      target: { value: "Draft" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save guidance" }));

    expect(mutate).toHaveBeenCalledWith(
      { projectId: "p1", guidance: "Draft" },
      expect.anything(),
    );
    expect(setOpen).toHaveBeenCalledWith(false);
  });
});
