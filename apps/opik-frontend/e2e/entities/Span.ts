import { API_URL } from "@e2e/config";
import { Tail } from "@e2e/utils";
import { Page } from "@playwright/test";
import { v7 as uuid } from "uuid";

import { FeedbackScore } from "./FeedbackScore";
import { Trace } from "./Trace";

export class Span {
  scores: FeedbackScore[] = [];

  constructor(
    readonly page: Page,
    readonly id: string,
    readonly name: string,
    readonly type: SPAN_TYPE,
    readonly trace: Trace,
  ) {}

  async addScore(...args: Tail<Parameters<typeof FeedbackScore.create>>) {
    const score = await FeedbackScore.create(
      { type: "span", entity: this },
      ...args,
    );
    this.scores.push(score);
    return score;
  }

  static async create(
    trace: Trace,
    name: string,
    type: SPAN_TYPE,
    params: object = {},
  ) {
    const id = (params as { id?: string })?.id ?? uuid();

    await trace.page.request.post(`${API_URL}spans`, {
      data: {
        id,
        name,
        project_name: trace.project.name,
        trace_id: trace.id,
        type,
        start_time: new Date().toISOString(),
        ...params,
      },
    });

    return new Span(trace.page, id, name, type, trace);
  }

  async destroy() {
    await this.page.request.delete(`${API_URL}spans/${this.id}`);
  }
}

export type SPAN_TYPE = "general" | "tool" | "llm";
