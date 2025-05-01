# Optimizers

## Overview
The Opik Optimizer offers a range of powerful optimizers designed to enhance prompt effectiveness. Each optimizer employs unique strategies to address different optimization needs.

### FewShotBayesianOptimizer
The `FewShotBayesianOptimizer` utilizes Bayesian optimization combined with few-shot learning to refine prompts. It is designed to work specifically with chat prompts and leverages Optuna for hyperparameter optimization. The optimizer selects a subset of examples from a dataset to construct a prompt, which is then evaluated using a specified metric. This approach is particularly effective for scenarios requiring precise prompt engineering with limited training data.

**Technical Explanation**: The `FewShotBayesianOptimizer` is initialized with parameters such as the model, project name, and configuration settings for few-shot learning. It uses Optuna to perform Bayesian optimization, selecting a number of examples from the dataset to construct a prompt. The optimization process involves evaluating each configuration using a specified metric, allowing it to converge on an optimal prompt configuration. The few-shot learning aspect enables it to perform well even with a limited number of examples.

### MiproOptimizer
The `MiproOptimizer` employs a multi-agent approach to optimize prompts. It integrates with DSPy and uses a combination of tools and configurations to evaluate and refine prompts. The optimizer is capable of handling complex optimization tasks and multi-step reasoning by leveraging diverse perspectives through agent collaboration. It supports both string-based prompts and programmatic prompts, allowing for flexible optimization strategies.

**Technical Explanation**: MiproOptimizer uses a multi-agent system where each agent can evaluate and refine prompts independently. This collaborative approach allows for diverse perspectives and solutions, making it suitable for complex tasks. The integration with DSPy provides additional tools and configurations to enhance the optimization process.

### Metaprompter
The `MetaPromptOptimizer` is designed to improve prompts using a meta-prompting approach. It employs a combination of bootstrapped demonstrations and constrained optimization strategies to enhance prompt effectiveness. This optimizer is particularly suited for tasks that require structured and systematic prompt improvements.

**Technical Explanation**: The `MetaPromptOptimizer` initializes with parameters such as the model for evaluation, reasoning model, and configuration settings. It evaluates prompts using a specified dataset and metric configuration, constructing prompts with templates. The optimization process involves generating candidate prompts, evaluating them, and selecting the best-performing prompt based on scores. It supports multi-threaded evaluation for efficiency and uses logging for progress tracking.


## Feature Comparison

| Feature | FewShotBayesian | Mipro | Metaprompter |
|---------|----------------|-------|--------------|
| Multi-agent Support | ❌ | ✅ | ❌ |
| Few-shot Learning | ✅ | ✅ | ❌ |
| Bayesian Optimization | ✅ | ❌ | ❌ |
| Dynamic Example Selection | ✅ | ✅ | ✅ |
| Multi-threaded Evaluation | ✅ | ✅ | ✅ |

## Next Steps

- Learn about [Dataset Requirements](./04-datasets-and-testing.md) for effective optimization.
- Explore [Configuration Options](./05-configuration-and-usage.md) for detailed setup instructions.
- Check [FAQ](./06-faq.md) for common questions and troubleshooting tips.