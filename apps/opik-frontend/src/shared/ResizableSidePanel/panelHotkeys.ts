const LAYER_SELECTOR =
  '[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]';
const OPEN_MODAL_SELECTOR =
  '[role="dialog"][data-state="open"], [role="alertdialog"][data-state="open"]';
// Radix popovers use role="dialog" too, but they are not modal: the panel
// behind an open popover stays in use.
const POPOVER_WRAPPER_SELECTOR = "[data-radix-popper-content-wrapper]";
// Tabs and toggle buttons are left out on purpose: after clicking a tab or a
// score, ← → must still move the panel to the next trace.
const OWN_ARROWS_SELECTOR = '[role="textbox"], [role="slider"]';
// A closed select opens on ↑ ↓ but does nothing on ← →, so those still move
// the panel, as on a tab.
const OWN_VERTICAL_ARROWS_SELECTOR = '[role="combobox"]';

const targetElement = (event: KeyboardEvent) =>
  event.target instanceof Element ? event.target : null;

const isKeyFromLayerAbove = (event: KeyboardEvent, nodeInPanel: Node) => {
  const layer = targetElement(event)?.closest(LAYER_SELECTOR);
  return Boolean(layer && !layer.contains(nodeInPanel));
};

// Some dialogs keep focus where it was when they opened (an image preview
// leaves it on the thumbnail), so the key's target alone can still point
// into the panel while a modal covers it.
const isModalOpenAbove = (nodeInPanel: Node) =>
  Array.from(document.querySelectorAll(OPEN_MODAL_SELECTOR)).some(
    (modal) =>
      !modal.contains(nodeInPanel) &&
      !modal.parentElement?.matches(POPOVER_WRAPPER_SELECTOR),
  );

export const isKeyForLayerAbovePanel = (
  event: KeyboardEvent,
  nodeInPanel: Node | null,
) =>
  Boolean(
    nodeInPanel &&
      (isKeyFromLayerAbove(event, nodeInPanel) ||
        isModalOpenAbove(nodeInPanel)),
  );

const isVerticalArrow = (event: KeyboardEvent) =>
  event.key === "ArrowUp" || event.key === "ArrowDown";

export const isArrowKeyForFocusedControl = (event: KeyboardEvent) => {
  const target = targetElement(event);
  if (!target || !event.key.startsWith("Arrow")) return false;
  return Boolean(
    target.closest(OWN_ARROWS_SELECTOR) ||
      (isVerticalArrow(event) && target.closest(OWN_VERTICAL_ARROWS_SELECTOR)),
  );
};
