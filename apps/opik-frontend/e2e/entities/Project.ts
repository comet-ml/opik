import { API_URL } from "@e2e/config";
import { Tail } from "@e2e/utils";
import { Page } from "@playwright/test";

import { Trace } from "./Trace";

export class Project {
  traces: Trace[] = [];

  constructor(
    readonly page: Page,
    readonly id: string,
    readonly name: string,
  ) {}

  async addTrace(...args: Tail<Parameters<typeof Trace.create>>) {
    const trace = await Trace.create(this, ...args);
    this.traces.push(trace);
    return trace;
  }

  static async create(page: Page, name: string, description?: string) {
    await page.request.post(`${API_URL}projects`, {
      data: {
        description,
        name,
      },
    });

    const result = await page.request.get(`${API_URL}projects`, {
      params: { name },
    });
    const {
      content: [project],
    } = await result.json();

    return new Project(page, project.id as string, name);
  }

  async destroy() {
    await this.page.request.delete(`${API_URL}projects/${this.id}`);
  }
}
