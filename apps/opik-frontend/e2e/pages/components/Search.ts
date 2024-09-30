import { Page } from "@playwright/test";

export type SearchData = string;

export class Search {
  readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  async search(value: SearchData) {
    await this.page.getByTestId("search-input").fill(value);
  }
}
