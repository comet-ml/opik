import type * as OpikApi from "@/rest_api/api";

export interface BlueprintData {
  id: string;
  type: OpikApi.AgentBlueprintPublicType;
  description?: string;
  envs?: string[];
  createdBy?: string;
  createdAt?: Date;
  values: OpikApi.AgentConfigValuePublic[];
}

export class Blueprint {
  readonly id: string;
  readonly type: OpikApi.AgentBlueprintPublicType;
  readonly description?: string;
  readonly envs?: string[];
  readonly createdBy?: string;
  readonly createdAt?: Date;
  private readonly _rawValues: OpikApi.AgentConfigValuePublic[];

  constructor(data: BlueprintData) {
    this.id = data.id;
    this.type = data.type;
    this.description = data.description;
    this.envs = data.envs;
    this.createdBy = data.createdBy;
    this.createdAt = data.createdAt;
    this._rawValues = data.values;
  }

  static fromApiResponse(response: OpikApi.AgentBlueprintPublic): Blueprint {
    if (!response.id) {
      throw new Error("Invalid API response: missing required field 'id'");
    }
    return new Blueprint({
      id: response.id,
      type: response.type,
      description: response.description,
      envs: response.envs,
      createdBy: response.createdBy,
      createdAt: response.createdAt,
      values: response.values,
    });
  }

  get values(): Record<string, string> {
    const result: Record<string, string> = {};
    for (const v of this._rawValues) {
      result[v.key] = v.value;
    }
    return result;
  }

  get(key: string): string | undefined;
  get(key: string, defaultValue: string): string;
  get(key: string, defaultValue?: string): string | undefined {
    const entry = this._rawValues.find((v) => v.key === key);
    return entry !== undefined ? entry.value : defaultValue;
  }

  keys(): string[] {
    return this._rawValues.map((v) => v.key);
  }
}
