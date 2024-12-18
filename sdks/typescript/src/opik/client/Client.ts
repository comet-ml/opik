import { loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiClient } from "@/rest_api";
import type { Trace as ITrace, ProjectPublic } from "@/rest_api/api";
import { SavedTrace, Trace } from "@/tracer/Trace";
import { v7 as uuid } from "uuid";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

export class OpikClient {
  public apiClient: OpikApiClient;
  public config: OpikConfig;
  private existingProjects: Map<string, ProjectPublic> = new Map();

  constructor(explicitConfig?: Partial<OpikConfig>) {
    this.config = loadConfig(explicitConfig);

    this.apiClient = new OpikApiClient({
      apiKey: this.config.apiKey,
      environment: this.config.host,
      workspaceName: this.config.workspaceName,
    });
  }
  public loadProject = async (projectName: string): Promise<ProjectPublic> => {
    if (this.existingProjects.has(projectName)) {
      return this.existingProjects.get(projectName)!;
    }

    const { data: projectsPage } = await this.apiClient.projects
      .findProjects({ name: projectName })
      .asRaw();

    const project = projectsPage.content?.find(
      (p) => p.name.toLowerCase() === projectName.toLowerCase()
    );

    if (project) {
      this.existingProjects.set(projectName, project);
      return project;
    }

    // Create the project if it doesn't exist
    await this.apiClient.projects
      .createProject({
        name: projectName,
      })
      .asRaw();

    return this.loadProject(projectName);
  };

  public trace = async (trace: TraceData) => {
    const projectName = trace.projectName ?? this.config.projectName;
    const traceWithId: SavedTrace = {
      id: uuid(),
      startTime: new Date(),
      ...trace,
      projectName,
    };

    await this.loadProject(projectName);
    await this.apiClient.traces.createTrace(traceWithId).asRaw();

    return new Trace(traceWithId, this);
  };
}
