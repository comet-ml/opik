import { API_URL } from "@e2e/config";
import { Tail } from "@e2e/utils";
import { Page } from "@playwright/test";

import { DatasetItem } from "./DatasetItem";

export class Dataset {
  items: DatasetItem[] = [];

  constructor(
    readonly page: Page,
    readonly id: string,
    readonly name: string,
    readonly original?: object,
  ) {}

  async addItem(...args: Tail<Parameters<typeof DatasetItem.create>>) {
    const item = await DatasetItem.create(this, ...args);
    this.items.push(item);
    return item;
  }

  static async create(page: Page, name: string, params: object = {}) {
    await page.request.post(`${API_URL}datasets`, {
      data: {
        name,
        ...params,
      },
    });

    const result = await page.request.get(`${API_URL}datasets`, {
      params: { name },
    });

    const {
      content: [dataset],
    } = await result.json();

    return new Dataset(page, dataset.id as string, name, dataset);
  }

  async destroy() {
    await this.page.request.delete(`${API_URL}datasets/${this.id}`);
  }
}
