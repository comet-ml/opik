import { ComponentProps, ReactNode, useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { Dialog, DialogContent, DialogTitle } from "@/ui/dialog";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Sheet, SheetContent, SheetTitle } from "@/ui/sheet";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import ResizableSidePanel from "./ResizableSidePanel";

const KEYS = {
  Escape: { key: "Escape", code: "Escape" },
  ArrowDown: { key: "ArrowDown", code: "ArrowDown" },
  ArrowUp: { key: "ArrowUp", code: "ArrowUp" },
  ArrowRight: { key: "ArrowRight", code: "ArrowRight" },
};

const buildNavigation = () => ({
  hasPrevious: true,
  hasNext: true,
  onChange: vi.fn(),
});

const PanelBody = ({ extra }: { extra?: ReactNode }) => (
  <>
    <button>Panel body</button>
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button>Open menu</button>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuItem>First item</DropdownMenuItem>
        <DropdownMenuItem>Second item</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
    <Select>
      <SelectTrigger aria-label="Thinking level">
        <SelectValue placeholder="Pick a level" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="low">Low</SelectItem>
        <SelectItem value="high">High</SelectItem>
      </SelectContent>
    </Select>
    <Tabs defaultValue="messages">
      <TabsList>
        <TabsTrigger value="messages">Messages</TabsTrigger>
        <TabsTrigger value="details">Details</TabsTrigger>
      </TabsList>
    </Tabs>
    <ToggleGroup type="single" aria-label="Score">
      <ToggleGroupItem value="yes">Yes</ToggleGroupItem>
      <ToggleGroupItem value="no">No</ToggleGroupItem>
    </ToggleGroup>
    <div role="textbox" tabIndex={0} aria-label="Read-only code" />
    {extra}
  </>
);

const renderPanel = (
  props: Partial<ComponentProps<typeof ResizableSidePanel>> = {},
  extra?: ReactNode,
) => {
  const onClose = vi.fn();
  const verticalNavigation = buildNavigation();
  const horizontalNavigation = buildNavigation();
  render(
    <TooltipProvider>
      <ResizableSidePanel
        panelId="test-panel"
        open
        onClose={onClose}
        verticalNavigation={verticalNavigation}
        horizontalNavigation={horizontalNavigation}
        {...props}
      >
        <PanelBody extra={extra} />
      </ResizableSidePanel>
    </TooltipProvider>,
  );
  return { onClose, verticalNavigation, horizontalNavigation };
};

const openMenu = () => {
  // Radix opens dropdowns on pointerdown, not click.
  fireEvent.pointerDown(
    screen.getByRole("button", { name: "Open menu" }),
    new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
  );
  return screen.getByRole("menu");
};

const openSelect = () => {
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  return screen.getByRole("listbox");
};

const confirmDialog = (
  <Dialog open>
    <DialogContent aria-describedby={undefined}>
      <DialogTitle>Delete trace</DialogTitle>
      <button>Cancel</button>
    </DialogContent>
  </Dialog>
);

const previewDialog = (
  <Dialog open>
    <DialogContent
      aria-describedby={undefined}
      onOpenAutoFocus={(event) => event.preventDefault()}
    >
      <DialogTitle>Image preview</DialogTitle>
    </DialogContent>
  </Dialog>
);

const openPopover = (
  <Popover open>
    <PopoverTrigger asChild>
      <button>Filter</button>
    </PopoverTrigger>
    <PopoverContent onOpenAutoFocus={(event) => event.preventDefault()}>
      Filter fields
    </PopoverContent>
  </Popover>
);

const panelBody = () =>
  screen.getByRole("button", { name: "Panel body", hidden: true });

const renderPanelInSheet = () => {
  const onClose = vi.fn();
  const verticalNavigation = buildNavigation();
  const SheetWithPanel = () => {
    const [sheetContent, setSheetContent] = useState<HTMLDivElement | null>(
      null,
    );
    return (
      <TooltipProvider>
        <Sheet open>
          <SheetContent
            ref={setSheetContent}
            header={<SheetTitle>Logs</SheetTitle>}
            aria-describedby={undefined}
            onEscapeKeyDown={(event) => event.preventDefault()}
          >
            <ResizableSidePanel
              panelId="test-panel"
              open
              onClose={onClose}
              verticalNavigation={verticalNavigation}
              container={sheetContent}
            >
              <button>Panel body</button>
            </ResizableSidePanel>
          </SheetContent>
        </Sheet>
      </TooltipProvider>
    );
  };
  render(<SheetWithPanel />);
  return { onClose, verticalNavigation };
};

describe("ResizableSidePanel hotkeys", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("closes the panel on Escape pressed in the panel", () => {
    const { onClose } = renderPanel();

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Panel body" }),
      KEYS.Escape,
    );

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["an open menu", openMenu, "menu"],
    ["an open select", openSelect, "listbox"],
  ])(
    "closes only %s on Escape, not the panel",
    (_label, openLayer, layerRole) => {
      const { onClose } = renderPanel();
      const layer = openLayer();

      fireEvent.keyDown(layer, KEYS.Escape);

      expect(screen.queryByRole(layerRole)).not.toBeInTheDocument();
      expect(onClose).not.toHaveBeenCalled();
    },
  );

  it("closes the panel on the next Escape after a menu was closed", () => {
    const { onClose } = renderPanel();
    fireEvent.keyDown(openMenu(), KEYS.Escape);

    fireEvent.keyDown(document.body, KEYS.Escape);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it.each([KEYS.ArrowDown, KEYS.ArrowRight])(
    "leaves $key in an open menu to the menu instead of moving the panel",
    (arrowKey) => {
      const { verticalNavigation, horizontalNavigation } = renderPanel();

      fireEvent.keyDown(openMenu(), arrowKey);

      expect(verticalNavigation.onChange).not.toHaveBeenCalled();
      expect(horizontalNavigation.onChange).not.toHaveBeenCalled();
    },
  );

  it.each([KEYS.ArrowDown, KEYS.ArrowRight, KEYS.Escape])(
    "ignores $key pressed in a dialog opened from the panel",
    (key) => {
      const { onClose, verticalNavigation, horizontalNavigation } = renderPanel(
        {},
        confirmDialog,
      );

      fireEvent.keyDown(screen.getByRole("button", { name: "Cancel" }), key);

      expect(verticalNavigation.onChange).not.toHaveBeenCalled();
      expect(horizontalNavigation.onChange).not.toHaveBeenCalled();
      expect(onClose).not.toHaveBeenCalled();
    },
  );

  it.each([KEYS.ArrowDown, KEYS.ArrowRight])(
    "ignores $key pressed in the panel while a modal it opened left focus there",
    (key) => {
      const { verticalNavigation, horizontalNavigation } = renderPanel(
        {},
        previewDialog,
      );

      fireEvent.keyDown(panelBody(), key);

      expect(verticalNavigation.onChange).not.toHaveBeenCalled();
      expect(horizontalNavigation.onChange).not.toHaveBeenCalled();
    },
  );

  it("still moves the panel while a popover opened from it is open", () => {
    const { horizontalNavigation } = renderPanel({}, openPopover);
    expect(screen.getByRole("dialog")).toHaveTextContent("Filter fields");

    fireEvent.keyDown(panelBody(), KEYS.ArrowRight);

    expect(horizontalNavigation.onChange).toHaveBeenCalledWith(1);
  });

  it("moves the panel on arrow keys pressed in the panel", () => {
    const { verticalNavigation } = renderPanel();

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Panel body" }),
      KEYS.ArrowDown,
    );

    expect(verticalNavigation.onChange).toHaveBeenCalledWith(1);
  });

  it.each([
    ["a focused tab", "tab", "Messages"],
    ["a focused score toggle", "radio", "Yes"],
  ])("moves the panel on → pressed on %s", (_label, role, name) => {
    const { horizontalNavigation } = renderPanel();

    fireEvent.keyDown(screen.getByRole(role, { name }), KEYS.ArrowRight);

    expect(horizontalNavigation.onChange).toHaveBeenCalledWith(1);
  });

  it.each([KEYS.ArrowDown, KEYS.ArrowUp])(
    "leaves $key on a closed select to the select",
    (arrowKey) => {
      const { verticalNavigation } = renderPanel();

      fireEvent.keyDown(screen.getByRole("combobox"), arrowKey);

      expect(verticalNavigation.onChange).not.toHaveBeenCalled();
    },
  );

  it("moves the panel on → pressed on a closed select", () => {
    const { horizontalNavigation } = renderPanel();

    fireEvent.keyDown(screen.getByRole("combobox"), KEYS.ArrowRight);

    expect(horizontalNavigation.onChange).toHaveBeenCalledWith(1);
  });

  it("leaves arrow keys in a text box to the text box, but closes on Escape", () => {
    const { onClose, verticalNavigation } = renderPanel();
    const textbox = screen.getByRole("textbox", { name: "Read-only code" });

    fireEvent.keyDown(textbox, KEYS.ArrowDown);
    fireEvent.keyDown(textbox, KEYS.Escape);

    expect(verticalNavigation.onChange).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes the panel and its tooltip on one Escape", () => {
    const { onClose } = renderPanel();
    const closeButton = screen.getByTestId("side-panel-close");
    fireEvent.focus(closeButton);
    expect(screen.getByRole("tooltip")).toBeInTheDocument();

    fireEvent.keyDown(closeButton, KEYS.Escape);

    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes a panel shown inside a sheet that keeps itself open on Escape", () => {
    const { onClose } = renderPanelInSheet();

    fireEvent.keyDown(panelBody(), KEYS.Escape);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("moves a panel shown inside a sheet on arrow keys", () => {
    const { verticalNavigation } = renderPanelInSheet();

    fireEvent.keyDown(panelBody(), KEYS.ArrowDown);

    expect(verticalNavigation.onChange).toHaveBeenCalledWith(1);
  });

  it("ignores Escape while hotkeys are turned off", () => {
    const { onClose } = renderPanel({ ignoreHotkeys: true });

    fireEvent.keyDown(document.body, KEYS.Escape);

    expect(onClose).not.toHaveBeenCalled();
  });
});

describe("ResizableSidePanel stacking", () => {
  it("stacks a panel opened from inside another panel above it", () => {
    render(
      <TooltipProvider>
        <ResizableSidePanel panelId="trial" open onClose={vi.fn()}>
          <ResizableSidePanel panelId="trace" open onClose={vi.fn()}>
            <button>Trace body</button>
          </ResizableSidePanel>
        </ResizableSidePanel>
      </TooltipProvider>,
    );
    const zIndexOf = (panelId: string) =>
      Number(screen.getByTestId(panelId).parentElement?.style.zIndex);

    expect(zIndexOf("trace")).toBeGreaterThan(zIndexOf("trial"));
  });
});
