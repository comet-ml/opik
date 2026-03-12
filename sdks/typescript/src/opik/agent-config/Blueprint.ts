import type { OpikClient } from "@/client/Client";
import type * as OpikApi from "@/rest_api/api";
import { Prompt } from "@/prompt/Prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { PromptVersion } from "@/prompt/PromptVersion";
import { deserializeValue } from "./typeHelpers";

export interface BlueprintData {
  id: string;
  type: OpikApi.AgentBlueprintPublicType;
  description?: string;
  envs?: string[];
  createdBy?: string;
  createdAt?: Date;
  values: OpikApi.AgentConfigValuePublic[];
  opik?: OpikClient;
}

export class Blueprint {
  readonly id: string;
  readonly type: OpikApi.AgentBlueprintPublicType;
  readonly description?: string;
  readonly envs?: string[];
  readonly createdBy?: string;
  readonly createdAt?: Date;
  private readonly _rawValues: OpikApi.AgentConfigValuePublic[];
  private readonly _opik?: OpikClient;
  private readonly _resolvedValues: Record<string, unknown>;

  constructor(data: BlueprintData) {
    this.id = data.id;
    this.type = data.type;
    this.description = data.description;
    this.envs = data.envs;
    this.createdBy = data.createdBy;
    this.createdAt = data.createdAt;
    this._rawValues = data.values;
    this._opik = data.opik;

    this._resolvedValues = {};
    for (const v of this._rawValues) {
      this._resolvedValues[v.key] = deserializeValue(v.value, v.type);
    }
  }

  static async fromApiResponse(
    response: OpikApi.AgentBlueprintPublic,
    opik?: OpikClient
  ): Promise<Blueprint> {
    if (!response.id) {
      throw new Error("Invalid API response: missing required field 'id'");
    }
    const blueprint = new Blueprint({
      id: response.id,
      type: response.type,
      description: response.description,
      envs: response.envs,
      createdBy: response.createdBy,
      createdAt: response.createdAt,
      values: response.values,
      opik,
    });
    await blueprint.resolvePrompts();
    return blueprint;
  }

  private async resolvePrompts(): Promise<void> {
    if (!this._opik) return;

    for (const v of this._rawValues) {
      if (v.type !== "prompt" && v.type !== "prompt_commit") continue;
      if (!v.value) continue;

      const promptDetail = await this._opik.api.prompts.getPromptByCommit(
        v.value
      );
      const versionDetail = promptDetail.requestedVersion;
      if (!versionDetail) continue;

      // The endpoint may omit promptId on requestedVersion; fall back to the parent id
      if (!versionDetail.promptId && promptDetail.id) {
        versionDetail.promptId = promptDetail.id;
      }

      if (v.type === "prompt") {
        if (versionDetail.templateStructure === "chat") {
          this._resolvedValues[v.key] = ChatPrompt.fromApiResponse(
            promptDetail,
            versionDetail,
            this._opik
          );
        } else {
          this._resolvedValues[v.key] = Prompt.fromApiResponse(
            promptDetail,
            versionDetail,
            this._opik
          );
        }
      } else {
        this._resolvedValues[v.key] = PromptVersion.fromApiResponse(
          promptDetail.name,
          versionDetail
        );
      }
    }
  }

  get values(): Record<string, unknown> {
    return { ...this._resolvedValues };
  }

  get(key: string): unknown;
  get<T>(key: string, defaultValue: T): T;
  get(key: string, defaultValue?: unknown): unknown {
    const val = this._resolvedValues[key];
    return val !== undefined ? val : defaultValue;
  }

  keys(): string[] {
    return this._rawValues.map((v) => v.key);
  }
}
