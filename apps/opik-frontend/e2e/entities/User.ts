import { Tail } from "@e2e/utils";
import { Page } from "@playwright/test";
import { Project } from "./Project";

export class User {
  projects: Project[] = [];

  constructor(readonly page: Page) {}

  async addProject(...args: Tail<Parameters<typeof Project.create>>) {
    const project = await Project.create(this.page, ...args);
    this.projects.push(project);
    return project;
  }
}
