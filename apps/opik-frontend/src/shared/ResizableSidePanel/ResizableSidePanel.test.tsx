import { ComponentProps } from "react";
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
import ResizableSidePanel from "./ResizableSidePanel";

const KEYS = {
  Escape: { key: "Escape", code: "Escape" },
  ArrowDown: { key: "ArrowDown", code: "ArrowDown" },
};

const renderPanel = (
  props: Partial<ComponentProps<typeof ResizableSidePanel>> = {},
) => {
  const onClose = vi.fn();
  const verticalNavigation = {
    hasPrevious: true,
    hasNext: true,
    onChange: vi.fn(),
  };
  render(
    <TooltipProvider>
      <ResizableSidePanel
        panelId="test-panel"
        open
        onClose={onClose}
        verticalNavigation={verticalNavigation}
        {...props}
      >
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
      </ResizableSidePanel>
    </TooltipProvider>,
  );
  return { onClose, verticalNavigation };
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

  it("leaves arrow keys to an open menu instead of moving the panel", () => {
    const { verticalNavigation } = renderPanel();

    fireEvent.keyDown(openMenu(), KEYS.ArrowDown);

    expect(verticalNavigation.onChange).not.toHaveBeenCalled();
  });

  it("moves the panel on arrow keys pressed in the panel", () => {
    const { verticalNavigation } = renderPanel();

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Panel body" }),
      KEYS.ArrowDown,
    );

    expect(verticalNavigation.onChange).toHaveBeenCalledWith(1);
  });

  it("ignores Escape while hotkeys are turned off", () => {
    const { onClose } = renderPanel({ ignoreHotkeys: true });

    fireEvent.keyDown(document.body, KEYS.Escape);

    expect(onClose).not.toHaveBeenCalled();
  });
});
