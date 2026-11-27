import { test, type Locator } from '@playwright/test';

/**
 * A table row's actions, as rendered by the shared `RowActions` component.
 *
 * The actions sit in a zero-width column at the row's right edge and show on
 * row hover. A row with one action shows it directly as an icon button; a row
 * with more shows a "More actions" button that expands every action into a
 * chip drawn in a portal (`row-actions-menu`), outside the row. Callers name
 * the action by its label and don't need to know which of the two the row
 * renders, since which one it is depends on the viewer's permissions.
 */
export async function openRowActions(row: Locator): Promise<Locator> {
  const actions = row.getByTestId('row-actions');
  await row.hover();
  const more = actions.getByRole('button', { name: 'More actions' });
  if ((await more.count()) === 0) return actions;

  await more.click();
  const menu = row.page().getByTestId('row-actions-menu');
  await menu.waitFor({ state: 'visible' });
  return menu;
}

/** Click one of a row's actions by label. Navigation actions are links, the rest buttons. */
export async function clickRowAction(
  row: Locator,
  name: string,
  role: 'button' | 'link' = 'button',
): Promise<void> {
  return test.step(`row action "${name}"`, async () => {
    const scope = await openRowActions(row);
    await scope.getByRole(role, { name, exact: true }).click();
  });
}
