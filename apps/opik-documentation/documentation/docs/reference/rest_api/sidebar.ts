import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebar: SidebarsConfig = {
  apisidebar: [
    {
      type: "doc",
      id: "reference/rest_api/opik-rest-api",
    },
    {
      type: "category",
      label: "Datasets",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/find-datasets",
          label: "Find datasets",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-dataset",
          label: "Create dataset",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-or-update-dataset-items",
          label: "Create/update dataset items",
          className: "api-method put",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-dataset-by-id",
          label: "Get dataset by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/update-dataset",
          label: "Update dataset by id",
          className: "api-method put",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-dataset",
          label: "Delete dataset by id",
          className: "api-method delete",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-dataset-by-name",
          label: "Delete dataset by name",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-dataset-items",
          label: "Delete dataset items",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/find-dataset-items-with-experiment-items",
          label: "Find dataset items with experiment items",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-dataset-by-identifier",
          label: "Get dataset by name",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-dataset-item-by-id",
          label: "Get dataset item by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-dataset-items",
          label: "Get dataset items",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/stream-dataset-items",
          label: "Stream dataset items",
          className: "api-method post",
        },
      ],
    },
    {
      type: "category",
      label: "Experiments",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/find-experiments",
          label: "Find experiments",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-experiment",
          label: "Create experiment",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-experiment-items",
          label: "Create experiment items",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-experiment-items",
          label: "Delete experiment items",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-experiment-by-id",
          label: "Get experiment by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-experiment-item-by-id",
          label: "Get experiment item by id",
          className: "api-method get",
        },
      ],
    },
    {
      type: "category",
      label: "Feedback-definitions",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/find-feedback-definitions",
          label: "Find Feedback definitions",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-feedback-definition",
          label: "Create feedback definition",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-feedback-definition-by-id",
          label: "Get feedback definition by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/update-feedback-definition",
          label: "Update feedback definition by id",
          className: "api-method put",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-feedback-definition-by-id",
          label: "Delete feedback definition by id",
          className: "api-method delete",
        },
      ],
    },
    {
      type: "category",
      label: "Projects",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/find-projects",
          label: "Find projects",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-project",
          label: "Create project",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-project-by-id",
          label: "Get project by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-project-by-id",
          label: "Delete project by id",
          className: "api-method delete",
        },
        {
          type: "doc",
          id: "reference/rest_api/update-project",
          label: "Update project by id",
          className: "api-method patch",
        },
      ],
    },
    {
      type: "category",
      label: "Spans",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/add-span-feedback-score",
          label: "Add span feedback score",
          className: "api-method put",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-spans-by-project",
          label: "Get spans by project_name or project_id and optionally by trace_id and/or type",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-span",
          label: "Create span",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-spans",
          label: "Create spans",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-span-by-id",
          label: "Get span by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-span-by-id",
          label: "Delete span by id",
          className: "api-method delete",
        },
        {
          type: "doc",
          id: "reference/rest_api/update-span",
          label: "Update span by id",
          className: "api-method patch",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-span-feedback-score",
          label: "Delete span feedback score",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/score-batch-of-spans",
          label: "Batch feedback scoring for spans",
          className: "api-method put",
        },
      ],
    },
    {
      type: "category",
      label: "Traces",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/add-trace-feedback-score",
          label: "Add trace feedback score",
          className: "api-method put",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-traces-by-project",
          label: "Get traces by project_name or project_id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-trace",
          label: "Create trace",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/create-traces",
          label: "Create traces",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/get-trace-by-id",
          label: "Get trace by id",
          className: "api-method get",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-trace-by-id",
          label: "Delete trace by id",
          className: "api-method delete",
        },
        {
          type: "doc",
          id: "reference/rest_api/update-trace",
          label: "Update trace by id",
          className: "api-method patch",
        },
        {
          type: "doc",
          id: "reference/rest_api/delete-trace-feedback-score",
          label: "Delete trace feedback score",
          className: "api-method post",
        },
        {
          type: "doc",
          id: "reference/rest_api/score-batch-of-traces",
          label: "Batch feedback scoring for traces",
          className: "api-method put",
        },
      ],
    },
    {
      type: "category",
      label: "UNTAGGED",
      items: [
        {
          type: "doc",
          id: "reference/rest_api/is-alive",
          label: "isAlive",
          className: "api-method get",
        },
      ],
    },
  ],
};

export default sidebar.apisidebar;
