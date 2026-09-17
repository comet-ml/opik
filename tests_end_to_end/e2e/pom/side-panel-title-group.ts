import { type Locator, type Page } from '@playwright/test';

/**
 * The title group of a `ResizableSidePanel` top bar — the close control, the
 * entity icon, the title, and (since OPIK-8342) the copy-ID / copy-link actions
 * that sit beside it. Not the whole bar: the panel's own action buttons, the
 * overflow menu included, are a sibling group at the bar's right edge.
 *
 * Why the copy actions need scoping at all: the trace panel renders a SECOND
 * set of them in its inner inspect toolbar, for whichever entity the span tree
 * has selected. With no span selected that toolbar shows the trace, so
 * `Copy trace ID` resolves to two buttons panel-wide and an unscoped lookup is
 * ambiguous. Scoping to the title group is what makes "the header's copy
 * action" addressable.
 *
 * Why it is structural: `ResizableSidePanelTopBar` carries no `data-testid` in
 * the deployed 2.2.68 build, and these specs are verified against a deployed
 * build, so they cannot depend on an attribute that build does not have. The
 * anchor is the Close control — the one element only this group has — and the
 * group is the innermost `div` containing it. Adding
 * `data-testid="side-panel-title-group"` to the component and moving this to
 * `getByTestId` is the right follow-up.
 */
export function sidePanelTitleGroup(page: Page, panelRoot: Locator): Locator {
  return panelRoot
    .locator('div')
    .filter({ has: page.getByRole('button', { name: 'Close', exact: true }) })
    .last();
}
