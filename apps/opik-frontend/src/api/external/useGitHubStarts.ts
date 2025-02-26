import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import axios from "axios";
import { QueryConfig } from "@/api/api";

const api = axios.create();

export interface RepositoryResponse {
  id: number;
  node_id: string;
  name: string;
  full_name: string;
  private: boolean;
  owner: {
    login: string;
    node_id: string;
    avatar_url: string;
    gravatar_id: string;
    url: string;
    received_events_url: string;
    type: string;
  };
  html_url: string;
  description: string;
  fork: boolean;
  url: string;
  forks_url: string;
  keys_url: string;
  collaborators_url: string;
  teams_url: string;
  hooks_url: string;
  events_url: string;
  assignees_url: string;
  branches_url: string;
  tags_url: string;
  blobs_url: string;
  git_tags_url: string;
  issue_events_url: string;
  languages_url: string;
  stargazers_url: string;
  contributors_url: string;
  subscribers_url: string;
  subscription_url: string;
  commits_url: string;
  git_commits_url: string;
  comments_url: string;
  issue_comment_url: string;
  contents_url: string;
  compare_url: string;
  merges_url: string;
  archive_url: string;
  downloads_url: string;
  issues_url: string;
  pulls_url: string;
  milestones_url: string;
  notifications_url: string;
  labels_url: string;
  releases_url: string;
  deployments_url: string;
  created_at: string;
  updated_at: string;
  pushed_at: string;
  git_url: string;
  ssh_url: string;
  clone_url: string;
  svn_url: string;
  homepage: string;
  size: number;
  stargazers_count: number;
  watchers_count: number;
  language: string;
  forks_count: number;
  open_issues_count: number;
  license: {
    key: string;
    name: string;
    url: string;
    node_id: string;
  };
  forks: number;
  open_issues: number;
  watchers: number;
  default_branch: string;
}

const getGitHubStarts = async ({ signal }: QueryFunctionContext) => {
  try {
    const { data } = await api.get<RepositoryResponse | null>(
      "https://api.github.com/repos/comet-ml/opik",
      {
        signal,
      },
    );

    return data;
  } catch (e) {
    return null;
  }
};

export default function useGitHubStarts(
  params: Record<string, unknown>,
  options?: QueryConfig<RepositoryResponse | null>,
) {
  return useQuery({
    queryKey: ["github-starts", params],
    queryFn: (context) => getGitHubStarts(context),
    ...options,
  });
}
