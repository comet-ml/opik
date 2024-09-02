import { API_URL } from "@e2e/config";
import { Tail } from "@e2e/utils";
import { Page } from "@playwright/test";
import { v7 as uuid } from "uuid";

import { FeedbackScore } from "./FeedbackScore";
import { Project } from "./Project";
import { Span } from "./Span";

export class Trace {
  scores: FeedbackScore[] = [];
  spans: Span[] = [];

  constructor(
    readonly page: Page,
    readonly id: string,
    readonly name: string,
    readonly project: Project,
  ) {}

  async addScore(...args: Tail<Parameters<typeof FeedbackScore.create>>) {
    const score = await FeedbackScore.create(
      { type: "trace", entity: this },
      ...args,
    );
    this.scores.push(score);
    return score;
  }

  async addSpan(...args: Tail<Parameters<typeof Span.create>>) {
    const span = await Span.create(this, ...args);
    this.spans.push(span);
    return span;
  }

  static async create(project: Project, name: string, params: object = {}) {
    const id = (params as { id?: string })?.id ?? uuid();

    await project.page.request.post(`${API_URL}traces`, {
      data: {
        id,
        name,
        project_name: project.name,
        start_time: new Date().toISOString(),
        ...params,
      },
    });

    return new Trace(project.page, id, name, project);
  }

  async destroy() {
    await this.page.request.delete(`${API_URL}traces/${this.id}`);
  }
}
