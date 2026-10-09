const LAYER_SELECTOR =
  '[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]';
// Tabs and toggle buttons are left out on purpose: after clicking a tab or a
// score, ← → must still move the panel to the next trace.
const OWN_ARROWS_SELECTOR = '[role="textbox"], [role="slider"]';

const targetElement = (event: KeyboardEvent) =>
  event.target instanceof Element ? event.target : null;

export const isKeyFromLayerAbovePanel = (
  event: KeyboardEvent,
  nodeInPanel: Node | null,
) => {
  const layer = targetElement(event)?.closest(LAYER_SELECTOR);
  return Boolean(layer && nodeInPanel && !layer.contains(nodeInPanel));
};

export const isArrowKeyForFocusedControl = (event: KeyboardEvent) =>
  event.key.startsWith("Arrow") &&
  Boolean(targetElement(event)?.closest(OWN_ARROWS_SELECTOR));
