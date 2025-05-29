import { Explainer } from "@/types/shared";

export enum EXPLAINER_ID {
  what_do_you_use_projects_for = "what_do_you_use_projects_for",
  what_are_feedback_scores = "what_are_feedback_scores",
  i_created_a_project_now_what = "i_created_a_project_now_what",
  what_are_traces = "what_are_traces",
  what_are_llm_calls = "what_are_llm_calls",
  what_are_threads = "what_are_threads",
  whats_online_evaluation = "whats_online_evaluation",
  i_added_traces_to_a_dataset_now_what = "i_added_traces_to_a_dataset_now_what",
  why_would_i_want_to_add_traces_to_a_dataset = "why_would_i_want_to_add_traces_to_a_dataset",
  hows_the_cost_estimated = "hows_the_cost_estimated",
  whats_the_error_column = "whats_the_error_column",
  what_happens_if_i_delete_a_trace = "what_happens_if_i_delete_a_trace",
  what_happens_if_i_delete_a_thread = "what_happens_if_i_delete_a_thread",
  whats_that_prompt_select = "whats_that_prompt_select",
  i_want_to_use_a_metric_i_dont_see_in_the_list = "i_want_to_use_a_metric_i_dont_see_in_the_list",
  what_happens_if_i_delete_a_rule = "what_happens_if_i_delete_a_rule",
  i_added_edited_a_new_online_evaluation_rule_now_what = "i_added_edited_a_new_online_evaluation_rule_now_what",
  what_are_these_elements_in_the_tree = "what_are_these_elements_in_the_tree",
  what_is_human_review = "what_is_human_review",
  whats_an_experiment = "whats_an_experiment",
  whats_a_prompt_commit = "whats_a_prompt_commit",
  i_run_an_experiment_now_what = "i_run_an_experiment_now_what",
  what_happens_if_i_delete_an_experiment = "what_happens_if_i_delete_an_experiment",
  what_are_experiment_items = "what_are_experiment_items",
  whats_the_experiment_configuration = "whats_the_experiment_configuration",
  what_does_it_mean_to_compare_my_experiments = "what_does_it_mean_to_compare_my_experiments",
  whats_the_dataset_item = "whats_the_dataset_item",
  whats_a_dataset = "whats_a_dataset",
  why_do_i_need_multiple_datasets = "why_do_i_need_multiple_datasets",
  what_format_is_this_to_add_my_dataset_item = "what_format_is_this_to_add_my_dataset_item",
  what_happens_if_i_delete_a_dataset = "what_happens_if_i_delete_a_dataset",
  what_happens_if_i_delete_a_dataset_item = "what_happens_if_i_delete_a_dataset_item",
  i_created_edited_my_dataset_now_what = "i_created_edited_my_dataset_now_what",
  i_added_my_dataset_items_now_what = "i_added_my_dataset_items_now_what",
  whats_the_prompt_library = "whats_the_prompt_library",
  how_do_i_use_this_prompt = "how_do_i_use_this_prompt",
  why_do_i_have_experiments_in_the_prompt_library = "why_do_i_have_experiments_in_the_prompt_library",
  what_are_commits = "what_are_commits",
  how_do_i_write_my_prompt = "how_do_i_write_my_prompt",
  what_happens_if_i_edit_my_prompt = "what_happens_if_i_edit_my_prompt",
  what_happens_if_i_delete_a_prompt = "what_happens_if_i_delete_a_prompt",
  i_created_edited_a_prompt_now_what = "i_created_edited_a_prompt_now_what",
  whats_the_playground = "whats_the_playground",
  what_does_reseat_playground_mean = "what_does_reseat_playground_mean",
  no_system_message = "no_system_message",
  whats_these_configuration_things = "whats_these_configuration_things",
  why_do_i_need_an_ai_provider = "why_do_i_need_an_ai_provider",
  what_does_the_dataset_do_here = "what_does_the_dataset_do_here",
  how_do_i_use_the_dataset_in_the_playground = "how_do_i_use_the_dataset_in_the_playground",
  whats_llm_as_a_judge = "whats_llm_as_a_judge",
  whats_a_code_metric = "whats_a_code_metric",
  whats_is_the_variable_mapping_for = "whats_is_the_variable_mapping_for",
  what_are_feedback_definitions = "what_are_feedback_definitions",
  should_i_use_numerical_or_categorical_score_definitions = "should_i_use_numerical_or_categorical_score_definitions",
  what_format_should_the_metadata_be = "what_format_should_the_metadata_be",
  whats_an_optimization_run = "whats_an_optimization_run",
  whats_the_best_score = "whats_the_best_score",
  what_happens_if_i_delete_an_ai_provider_config = "what_happens_if_i_delete_an_ai_provider_config",
  what_happens_if_i_edit_an_ai_provider = "what_happens_if_i_edit_an_ai_provider",
  what_happens_if_i_delete_a_project = "what_happens_if_i_delete_a_project",
  what_happens_if_i_delete_an_optimization_run = "what_happens_if_i_delete_an_optimization_run",
  what_happens_if_i_edit_a_rule = "what_happens_if_i_edit_a_rule",
  what_happens_if_i_delete_a_feedback_definition = "what_happens_if_i_delete_a_feedback_definition",
  why_would_i_want_to_create_a_new_project = "why_would_i_want_to_create_a_new_project",
  whats_the_commit_history = "whats_the_commit_history",
  why_would_i_compare_commits = "why_would_i_compare_commits",
  whats_the_optimizer = "whats_the_optimizer",
  what_are_trial_items = "what_are_trial_items",
  whats_the_evaluation_run_configuration = "whats_the_evaluation_run_configuration",
  i_run_an_optimization_run_now_what = "i_run_an_optimization_run_now_what",
}

export const EXPLAINERS_MAP: Record<EXPLAINER_ID, Explainer> = {
  [EXPLAINER_ID.what_do_you_use_projects_for]: {
    description:
      "Set up a new project to organize, isolate, and monitor different LLM workflows and apps.",
  },
  [EXPLAINER_ID.what_are_feedback_scores]: {
    description:
      "Feedback scores are evaluations of your LLM outputs, obtained via human review, online evaluation rules, or the SDK",
  },
  [EXPLAINER_ID.i_created_a_project_now_what]: {
    title: "Project created",
    description:
      "Log traces to your project to capture LLM interactions, analyze performance, run experiments, and improve your application over time.",
    docLink: "/tracing/log_traces",
  },
  [EXPLAINER_ID.what_are_traces]: {
    description:
      "A trace is a step-by-step record of how your LLM application processes a single input, including LLM calls and other operations.",
    docLink: "/tracing/log_traces",
  },
  [EXPLAINER_ID.what_are_llm_calls]: {
    description:
      "An LLM call is a single interaction with a language model - usually a prompt and its response. Use LLM calls to debug, monitor, and evaluate model behavior.",
  },
  [EXPLAINER_ID.what_are_threads]: {
    description:
      "A thread represents a full conversation session, grouping together multiple related traces. Use threads to review and evaluate entire interactions in chat-based applications.",
    docLink: "/tracing/log_chat_conversations",
    type: "help",
  },
  [EXPLAINER_ID.whats_online_evaluation]: {
    description:
      "Automatically score your production traces by defining LLM-as-a-Judge or code metrics.",
    docLink: "/production/rules",
  },
  [EXPLAINER_ID.i_added_traces_to_a_dataset_now_what]: {
    title: "Traces added to dataset",
    description:
      "Run experiments using your dataset to evaluate your LLM's performance and get insights into how your model behaves in live scenarios.",
  },
  [EXPLAINER_ID.why_would_i_want_to_add_traces_to_a_dataset]: {
    description:
      "Add traces to a dataset to evaluate and benchmark LLM outputs using real production data. You can then use these datasets in experiments to track how your LLM app's performance evolves over time.",
  },
  [EXPLAINER_ID.hows_the_cost_estimated]: {
    description:
      "Opik estimates the cost of each trace by calculating token usage across all LLM calls, using model-specific pricing.",
    docLink: "/tracing/cost_tracking",
    type: "help",
  },
  [EXPLAINER_ID.whats_the_error_column]: {
    description: "*?",
    type: "help",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_trace]: {
    title: "Delete traces",
    description:
      "Deleting a trace will also remove the trace data from related experiment samples. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_thread]: {
    title: "Delete thread",
    description:
      "Deleting a thread will also remove all traces linked to it and their data. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.whats_that_prompt_select]: {
    description:
      "Select the LLM-as-a-Judge prompt to use. You can use one of the prompts provided by Opik, or you can define your own.",
    docLink: "/production/rules",
    docHash: "#opiks-built-in-llm-as-a-judge-metrics",
    type: "help",
  },
  [EXPLAINER_ID.i_want_to_use_a_metric_i_dont_see_in_the_list]: {
    description:
      "Want more LLM-as-a-Judge metrics? Learn how to create your own metrics",
    docLink: "/production/rules",
    docHash: "#writing-your-own-llm-as-a-judge-metric",
    type: "help",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_rule]: {
    title: "Delete evaluation rule",
    description:
      "Deleting an online evaluation rule will stop scoring for all new traces. Existing traces that have already been scores won't be affected. This action can't be undone. Are you sure you want to continue?\n\nTip: To pause scoring without deleting, set the sampling rate to 0%.",
  },
  [EXPLAINER_ID.i_added_edited_a_new_online_evaluation_rule_now_what]: {
    title: "Evaluation rule set",
    description:
      "All new traces will now be automatically scored using this rule. You can view the results in the Traces table, the Metrics tab, and in each trace's detail view.",
  },
  [EXPLAINER_ID.what_are_these_elements_in_the_tree]: {
    description:
      "The span tree visualizes the sequence and structure of operations in a trace - including LLM calls, tool calls, guardrails, and more.",
  },
  [EXPLAINER_ID.what_is_human_review]: {
    description:
      "Human review fields let you manually rate LLM outputs. Use them to gather structured feedback and monitor quality over time.",
  },
  [EXPLAINER_ID.whats_an_experiment]: {
    description:
      "Experiments help you test prompts and models, compare versions, and track improvements or issues.",
    docLink: "/evaluation/overview",
  },
  [EXPLAINER_ID.whats_a_prompt_commit]: {
    description:
      "Prompt commits capture the exact prompt version used in an experiment, ensuring reproducibility and traceability of results. Manage your prompts in the Prompt library.",
    docLink: "/prompt_engineering/prompt_management",
    type: "help",
  },
  [EXPLAINER_ID.i_run_an_experiment_now_what]: {
    title: "Experiment started",
    description:
      "Analyze the results to identify strengths and weaknesses, then iterate by refining prompts, datasets, or evaluation rules to optimize your LLM application's performance.",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_an_experiment]: {
    title: "Delete experiment",
    description:
      "Deleting an experiment will remove all samples in the experiment. Related traces won't be affected. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.what_are_experiment_items]: {
    description:
      "Experiment items are individual evaluations that connect a dataset sample with its LLM output, feedback scores, and trace.",
    docLink: "/evaluation/overview",
    docHash: "#analyzing-evaluation-results",
  },
  [EXPLAINER_ID.whats_the_experiment_configuration]: {
    description:
      "The experiment configuration captures key settings, like the prompt, model, and temperature, to keep experiments reproducible and easy to analyze.",
    docLink: "/evaluation/concepts",
    docHash: "#experiment-configuration",
  },
  [EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments]: {
    description:
      "Compare experiments to understand how changes to prompts, models, or rules impact performance. Select at least two experiments from the same dataset to get started.",
    docLink: "/evaluation/overview",
    docHash: "#analyzing-evaluation-results",
    type: "help",
  },
  [EXPLAINER_ID.whats_the_dataset_item]: {
    description:
      "Dataset items are individual input samples in an experiment, each representing a test case your LLM app processes and evaluates.",
    docLink: "/evaluation/concepts",
    docHash: "#experiments",
    type: "help",
  },
  [EXPLAINER_ID.whats_a_dataset]: {
    description:
      "A dataset is a collection of input-output examples used to evaluate your LLM application's performance.",
    docLink: "/evaluation/concepts",
    docHash: "#datasets",
  },
  [EXPLAINER_ID.why_do_i_need_multiple_datasets]: {
    description:
      "Create different datasets to evaluate your LLM application in different contexts, scenarios, or environments.",
  },
  [EXPLAINER_ID.what_format_is_this_to_add_my_dataset_item]: {
    description:
      'Define input-output examples for evaluating your LLM. Use a valid JSON object with key-value pairs. Example: {"key": "value"}',
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_dataset]: {
    title: "Delete dataset",
    description:
      'Deleting this dataset will also remove all its items. Any experiments linked to it will be moved to a "Deleted dataset" group. This action can\'t be undone. Are you sure you want to continue?',
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_dataset_item]: {
    title: "Delete dataset items",
    description:
      "Deleting dataset items will also remove the related sample data from any linked experiments. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.i_created_edited_my_dataset_now_what]: {
    title: "Dataset created",
    description:
      "Add dataset items to build a comprehensive test set for your experiments and prepare for effective evaluation of your LLM application.",
  },
  [EXPLAINER_ID.i_added_my_dataset_items_now_what]: {
    title: "Dataset items added",
    description:
      "Use the dataset in experiments to evaluate your LLM's performance across diverse test cases to identify areas for improvement.",
  },
  [EXPLAINER_ID.whats_the_prompt_library]: {
    description:
      "Store and version prompts to maintain consistency and simplify experimentation across projects",
    docLink: "/prompt_engineering/prompt_management",
  },
  [EXPLAINER_ID.how_do_i_use_this_prompt]: {
    description:
      "To use a saved prompt from your library, select it when configuring an experiment or in the playground. You can also reference its latest version or a specific commit.",
    docLink: "/prompt_engineering/prompt_management",
    docHash: "#linking-prompts-to-experiments",
  },
  [EXPLAINER_ID.why_do_i_have_experiments_in_the_prompt_library]: {
    description:
      "Viewing experiments that used the same prompt helps identify how prompt variations impact results and refine your approach.",
  },
  [EXPLAINER_ID.what_are_commits]: {
    description:
      "Each edit in a prompt creates a new commit, ensuring version control and reproducibility.",
    docLink: "/prompt_engineering/prompt_management",
    docHash: "#managing-prompts-stored-in-code",
    type: "help",
  },
  [EXPLAINER_ID.how_do_i_write_my_prompt]: {
    description:
      "Write prompts as your LLM would receive them, including any dynamic placeholders or formatting.",
    docLink: "/agent_optimization/best-practices/prompt_engineering",
  },
  [EXPLAINER_ID.what_happens_if_i_edit_my_prompt]: {
    description:
      "Write prompts as your LLM would receive them, including any dynamic placeholders or formatting.\n\nEditing creates a new version automatically. View history in the Commits tab.",
    docLink: "/agent_optimization/best-practices/prompt_engineering",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_prompt]: {
    title: "Delete prompt",
    description:
      "Deleting a prompt will also remove all associated commits. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.i_created_edited_a_prompt_now_what]: {
    title: "Prompt saved",
    description:
      "Your changes were saved to the Prompt library. Test your prompt in the playground or use it in an experiment to evaluate performance and refine it before scaling.",
  },
  [EXPLAINER_ID.whats_the_playground]: {
    description:
      "Test before saving a prompt to the library or running a full experiment. Or use it to explore different prompt variations and see how your LLM responds in real time.",
    docLink: "/prompt_engineering/playground",
  },
  [EXPLAINER_ID.what_does_reseat_playground_mean]: {
    title: "Reset playground",
    description:
      "Resetting the Playground will discard all unsaved prompts. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.no_system_message]: {
    description:
      "Some providers like <provider> require a system message to define model behavior up front. Please provide a system message to set the context for the LLM.",
  },
  [EXPLAINER_ID.whats_these_configuration_things]: {
    title: "Model parameters",
    description:
      "The model configuration parameters allow you to control output behavior. Tuning these lets you balance between deterministic answers and more diverse, exploratory outputs.",
  },
  [EXPLAINER_ID.why_do_i_need_an_ai_provider]: {
    description:
      "Connect AI providers to send prompts and receive responses from different LLMs . Set up a provider to test prompts live and preview model behavior in the Playground, and to automatically score traces using online evaluation rules.",
  },
  [EXPLAINER_ID.what_does_the_dataset_do_here]: {
    description:
      "Run a prompt on a dataset in the Playground to preview its performance across multiple inputs.",
  },
  [EXPLAINER_ID.how_do_i_use_the_dataset_in_the_playground]: {
    description:
      "Use mustache syntax to reference dataset variables in your prompt. Example: {{question}}",
  },
  [EXPLAINER_ID.whats_llm_as_a_judge]: {
    description:
      "LLM-as-a-Judge uses a language model to score outputs based on custom natural language criteria like relevance, clarity, or factual accuracy.",
  },
  [EXPLAINER_ID.whats_a_code_metric]: {
    description:
      "A code metric is a Python function that scores LLM outputs using logic-based rules - like exact matches or keyword checks. Ideal for fast, deterministic evaluations without relying on another LLM.",
  },
  [EXPLAINER_ID.whats_is_the_variable_mapping_for]: {
    description:
      "Detected variables in your prompt (e.g., {{variable1}}) will appear below. For each one, select a field from a recent trace to map it. This will auto-fill the variable during rule execution.",
  },
  [EXPLAINER_ID.what_are_feedback_definitions]: {
    description:
      "Create custom fields to manually rate LLM outputs. Use them to collect structured feedback and track quality over time.",
  },
  [EXPLAINER_ID.should_i_use_numerical_or_categorical_score_definitions]: {
    title: "Categorical",
    description:
      'Use labels (e.g. "Good", "Bad") to classify outputs qualitatively.\n\nNumerical\nUse a numerical range (e.g. 1–5) to rate outputs quantitatively.',
  },
  [EXPLAINER_ID.what_format_should_the_metadata_be]: {
    description:
      'Use a valid JSON object with key-value pairs. Example: {"key": "value"}',
  },
  [EXPLAINER_ID.whats_an_optimization_run]: {
    description:
      "Optimization runs test multiple prompt variations to find the best one based on your metrics.",
    docLink: "/agent_optimization/overview",
  },
  [EXPLAINER_ID.whats_the_best_score]: {
    description:
      "Highest evaluation result achieved during the optimization run, based on your selected metric.",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_an_ai_provider_config]: {
    title: "Delete configuration",
    description:
      "This configuration is shared across the workspace. Deleting it will remove access for everyone. This action can't be undone. Are you sure you want to proceed?",
  },
  [EXPLAINER_ID.what_happens_if_i_edit_an_ai_provider]: {
    title: "Editing an existing key",
    description:
      "A key is already set for this provider. Since AI provider configurations are workspace-wide, adding a new key will overwrite the existing one for all users.",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_project]: {
    title: "Delete project",
    description:
      "Deleting a project will also remove all the traces and their data. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_an_optimization_run]: {
    title: "Delete optimization run",
    description:
      "Deleting an optimization run will remove all its trials and their data. Related traces won't be affected. This action can't be undone. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.what_happens_if_i_edit_a_rule]: {
    title: "Editing an existing rule",
    description:
      "Changes will only apply to new traces. Existing traces won't be affected.",
  },
  [EXPLAINER_ID.what_happens_if_i_delete_a_feedback_definition]: {
    title: "Delete feedback definition",
    description:
      "This action can't be undone. Existing scored traces won't be affected. Are you sure you want to continue?",
  },
  [EXPLAINER_ID.why_would_i_want_to_create_a_new_project]: {
    description:
      "Organize your work by app, integration, or environment. Projects keep traces, metrics, and evaluations separate, making it easier to manage, monitor, and collaborate.",
  },
  [EXPLAINER_ID.whats_the_commit_history]: {
    description:
      "The commit history tracks every change made to a prompt, including edits, timestamps, and authors.",
  },
  [EXPLAINER_ID.why_would_i_compare_commits]: {
    description:
      "Compare prompt commits to understand how changes affect output quality and performance. Select at least two commits to get started.",
    docLink: "/evaluation/evaluate_prompt",
    type: "help",
  },
  [EXPLAINER_ID.whats_the_optimizer]: {
    description:
      "An optimizer is a built-in algorithm from the Opik Agent Optimizer SDK that improves prompt effectiveness. Each one uses its own strategy and configurable settings to target specific optimization goals.",
    docLink: "/agent_optimization/opik_optimizer/concepts",
    type: "help",
  },
  [EXPLAINER_ID.what_are_trial_items]: {
    description:
      "Trial items are dataset samples processed during a trial. Each one generates an output and score that contribute to the trial's results.",
    docLink: "/agent_optimization/opik_optimizer/concepts",
  },
  [EXPLAINER_ID.whats_the_evaluation_run_configuration]: {
    description:
      "The evaluation run configuration captures key settings - like the metric, and optimizer - to keep evaluation runs reproducible and easy to analyze.",
  },
  [EXPLAINER_ID.i_run_an_optimization_run_now_what]: {
    title: "Optimization run started",
    description:
      "Opik will test different prompt and model settings to find the best-performing combination. Results may take a while depending on the algorithm and data volume. Once complete, review them to improve your LLM application.",
  },
};
