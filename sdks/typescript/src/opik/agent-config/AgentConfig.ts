import type { OpikClient } from "@/client/Client";
import { OpikApiError } from "@/rest_api";
import * as OpikApi from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import { Blueprint } from "./Blueprint";

export interface CreateBlueprintOptions {
  values: Record<string, string>;
  description?: string;
}

export interface GetBlueprintOptions {
  id?: string;
  env?: string;
  maskId?: string;
}

export class AgentConfig {
  private readonly projectName: string;
  private readonly opik: OpikClient;

  constructor(projectName: string, opik: OpikClient) {
    this.projectName = projectName;
    this.opik = opik;
  }

  private async getProjectId(): Promise<string> {
    const project = await this.opik.api.projects.retrieveProject({
      name: this.projectName,
    });
    if (!project?.id) {
      throw new Error(`Project "${this.projectName}" not found`);
    }
    return project.id;
  }

  private async createBlueprintInternal(
    options: CreateBlueprintOptions,
    type: OpikApi.AgentBlueprintWriteType
  ): Promise<Blueprint> {
    const id = generateId();
    const values: OpikApi.AgentConfigValueWrite[] = Object.entries(
      options.values
    ).map(([key, value]) => ({
      key,
      value,
      type: OpikApi.AgentConfigValueWriteType.String,
    }));

    logger.debug(`Creating ${type} for project "${this.projectName}"`);

    await this.opik.api.agentConfigs.createAgentConfig({
      id,
      projectName: this.projectName,
      blueprint: {
        id,
        type,
        description: options.description,
        values,
      },
    });

    const response = await this.opik.api.agentConfigs.getBlueprintById(id);
    return Blueprint.fromApiResponse(response);
  }

  async createBlueprint(options: CreateBlueprintOptions): Promise<Blueprint> {
    return this.createBlueprintInternal(
      options,
      OpikApi.AgentBlueprintWriteType.Blueprint
    );
  }

  async createMask(options: CreateBlueprintOptions): Promise<string> {
    const id = generateId();
    const values: OpikApi.AgentConfigValueWrite[] = Object.entries(
      options.values
    ).map(([key, value]) => ({
      key,
      value,
      type: OpikApi.AgentConfigValueWriteType.String,
    }));

    logger.debug(`Creating mask for project "${this.projectName}"`);

    await this.opik.api.agentConfigs.createAgentConfig({
      id,
      projectName: this.projectName,
      blueprint: {
        id,
        type: OpikApi.AgentBlueprintWriteType.Mask,
        description: options.description,
        values,
      },
    });

    return id;
  }

  async getBlueprint(
    options: GetBlueprintOptions = {}
  ): Promise<Blueprint | null> {
    const { id, env, maskId } = options;

    try {
      let response: OpikApi.AgentBlueprintPublic;

      if (id) {
        logger.debug(
          `Getting blueprint by ID "${id}" for project "${this.projectName}"`
        );
        response = await this.opik.api.agentConfigs.getBlueprintById(id, {
          maskId,
        });
      } else if (env) {
        const projectId = await this.getProjectId();
        logger.debug(
          `Getting blueprint by env "${env}" for project "${this.projectName}"`
        );
        response = await this.opik.api.agentConfigs.getBlueprintByEnv(
          env,
          projectId,
          { maskId }
        );
      } else {
        const projectId = await this.getProjectId();
        logger.debug(`Getting latest blueprint for project "${this.projectName}"`);
        response = await this.opik.api.agentConfigs.getLatestBlueprint(
          projectId,
          { maskId }
        );
      }

      return Blueprint.fromApiResponse(response);
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return null;
      }
      logger.error(`Failed to get blueprint for project "${this.projectName}"`, {
        error,
      });
      throw error;
    }
  }

  async tagBlueprintWithEnv(
    blueprintId: string,
    env: string
  ): Promise<void> {
    const projectId = await this.getProjectId();
    logger.debug(
      `Tagging blueprint "${blueprintId}" with env "${env}" for project "${this.projectName}"`
    );
    await this.opik.api.agentConfigs.createOrUpdateEnvs({
      projectId,
      envs: [{ envName: env, blueprintId }],
    });
  }
}
