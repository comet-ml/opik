import { API_URL } from "@e2e/config";
import { Tail } from "@e2e/utils";
import { Page } from "@playwright/test";
import { v7 as uuid } from "uuid";

import { FeedbackScore } from "./FeedbackScore";
import { Trace } from "./Trace";

export class Span {
  scores: FeedbackScore[] = [];
  spans: Span[] = [];

  constructor(
    readonly page: Page,
    readonly id: string,
    readonly name: string,
    readonly type: SPAN_TYPE,
    readonly trace: Trace,
    readonly original?: object,
  ) {}

  async addScore(...args: Tail<Parameters<typeof FeedbackScore.create>>) {
    const score = await FeedbackScore.create(
      { type: "span", entity: this },
      ...args,
    );
    this.scores.push(score);
    return score;
  }

  async addSpan(...args: Tail<Parameters<typeof Span.create>>) {
    const span = await Span.create(this.trace, ...args);
    this.spans.push(span);
    return span;
  }

  static async create(trace: Trace, name: string, params: object = {}) {
    const { id = uuid(), type } = params as { id?: string; type: SPAN_TYPE };

    const startTime = new Date().getTime();

    await trace.page.request.post(`${API_URL}spans`, {
      data: {
        id,
        name,
        project_name: trace.project.name,
        trace_id: trace.id,
        type,
        start_time: new Date(startTime).toISOString(),
        end_time: new Date(startTime + 2560).toISOString(),
        ...params,
      },
    });

    const result = await trace.page.request.get(`${API_URL}spans/${id}`);
    const span = await result.json();

    return new Span(trace.page, id, name, type, trace, span);
  }

  async destroy() {
    await this.page.request.delete(`${API_URL}spans/${this.id}`);
  }
}

export type SPAN_TYPE = "general" | "tool" | "llm";
