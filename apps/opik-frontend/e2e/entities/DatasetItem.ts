import { API_URL } from "@e2e/config";
import { Page } from "@playwright/test";
import { v7 as uuid } from "uuid";

import { Dataset } from "./Dataset";

export class DatasetItem {
  constructor(
    readonly page: Page,
    readonly id: string,
  ) {}

  static async create(dataset: Dataset, params: object = {}) {
    const { id = uuid() } = params as { id?: string };

    await dataset.page.request.put(`${API_URL}datasets/items`, {
      data: {
        dataset_id: dataset.id,
        items: [
          {
            ...params,
          },
        ],
      },
    });

    return new DatasetItem(dataset.page, id);
  }

  async destroy() {
    await this.page.request.post(`${API_URL}datasets/items/delete`, {
      data: {
        item_ids: [this.id],
      },
    });
  }
}
