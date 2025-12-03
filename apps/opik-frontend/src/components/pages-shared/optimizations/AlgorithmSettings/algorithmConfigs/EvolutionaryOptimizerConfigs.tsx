import React from "react";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { EvolutionaryOptimizerParameters } from "@/types/optimizations";
import { DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface EvolutionaryOptimizerConfigsProps {
  configs: Partial<EvolutionaryOptimizerParameters>;
  onChange: (configs: Partial<EvolutionaryOptimizerParameters>) => void;
}

const EvolutionaryOptimizerConfigs = ({
  configs,
  onChange,
}: EvolutionaryOptimizerConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <SliderInputControl
        value={
          configs.population_size ??
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.POPULATION_SIZE
        }
        onChange={(v) => onChange({ ...configs, population_size: v })}
        id="population_size"
        min={1}
        max={100}
        step={1}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.POPULATION_SIZE}
        label="Population size"
        tooltip="Number of candidate solutions in each generation"
      />

      <SliderInputControl
        value={
          configs.num_generations ??
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.NUM_GENERATIONS
        }
        onChange={(v) => onChange({ ...configs, num_generations: v })}
        id="num_generations"
        min={1}
        max={50}
        step={1}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.NUM_GENERATIONS}
        label="Number of generations"
        tooltip="How many iterations of evolution to run"
      />

      <SliderInputControl
        value={
          configs.mutation_rate ??
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.MUTATION_RATE
        }
        onChange={(v) => onChange({ ...configs, mutation_rate: v })}
        id="mutation_rate"
        min={0}
        max={1}
        step={0.01}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.MUTATION_RATE}
        label="Mutation rate"
        tooltip="Probability of random changes to candidate solutions (0-1)"
      />

      <SliderInputControl
        value={
          configs.crossover_rate ??
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.CROSSOVER_RATE
        }
        onChange={(v) => onChange({ ...configs, crossover_rate: v })}
        id="crossover_rate"
        min={0}
        max={1}
        step={0.01}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.CROSSOVER_RATE}
        label="Crossover rate"
        tooltip="Probability of combining two solutions (0-1)"
      />

      <SliderInputControl
        value={
          configs.tournament_size ??
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.TOURNAMENT_SIZE
        }
        onChange={(v) => onChange({ ...configs, tournament_size: v })}
        id="tournament_size"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.TOURNAMENT_SIZE}
        label="Tournament size"
        tooltip="Number of candidates competing in each selection round"
      />

      <SliderInputControl
        value={
          configs.elitism_size ??
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ELITISM_SIZE
        }
        onChange={(v) => onChange({ ...configs, elitism_size: v })}
        id="elitism_size"
        min={0}
        max={20}
        step={1}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ELITISM_SIZE}
        label="Elitism size"
        tooltip="Number of best solutions preserved unchanged in each generation"
      />

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="adaptive_mutation"
            checked={
              configs.adaptive_mutation ??
              DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ADAPTIVE_MUTATION
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, adaptive_mutation: checked === true })
            }
          />
          <Label htmlFor="adaptive_mutation" className="cursor-pointer text-sm">
            Adaptive mutation
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_adaptive_mutation]}
          />
        </div>
      </div>

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="enable_moo"
            checked={
              configs.enable_moo ??
              DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_MOO
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, enable_moo: checked === true })
            }
          />
          <Label htmlFor="enable_moo" className="cursor-pointer text-sm">
            Enable multi-objective optimization
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_enable_moo]}
          />
        </div>
      </div>

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="enable_llm_crossover"
            checked={
              configs.enable_llm_crossover ??
              DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_LLM_CROSSOVER
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, enable_llm_crossover: checked === true })
            }
          />
          <Label
            htmlFor="enable_llm_crossover"
            className="cursor-pointer text-sm"
          >
            Enable LLM crossover
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_enable_llm_crossover]}
          />
        </div>
      </div>

      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="output_style_guidance" className="text-sm">
            Output style guidance
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_output_style_guidance]}
          />
        </div>
        <Input
          id="output_style_guidance"
          value={
            configs.output_style_guidance ??
            DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.OUTPUT_STYLE_GUIDANCE
          }
          onChange={(e) =>
            onChange({ ...configs, output_style_guidance: e.target.value })
          }
          placeholder="e.g., concise, formal, technical"
          dimension="sm"
        />
      </div>

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="infer_output_style"
            checked={
              configs.infer_output_style ??
              DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.INFER_OUTPUT_STYLE
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, infer_output_style: checked === true })
            }
          />
          <Label
            htmlFor="infer_output_style"
            className="cursor-pointer text-sm"
          >
            Infer output style
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_infer_output_style]}
          />
        </div>
      </div>

      <SliderInputControl
        value={
          configs.n_threads ?? DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.N_THREADS
        }
        onChange={(v) => onChange({ ...configs, n_threads: v })}
        id="n_threads"
        min={1}
        max={16}
        step={1}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.N_THREADS}
        label="Number of threads"
        tooltip="Parallel threads for faster optimization"
      />

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="verbose"
            checked={
              configs.verbose ?? DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.VERBOSE
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, verbose: checked === true })
            }
          />
          <Label htmlFor="verbose" className="cursor-pointer text-sm">
            Verbose
          </Label>
          <ExplainerIcon {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_verbose]} />
        </div>
      </div>

      <SliderInputControl
        value={configs.seed ?? DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.SEED}
        onChange={(v) => onChange({ ...configs, seed: v })}
        id="seed"
        min={0}
        max={1000}
        step={1}
        defaultValue={DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.SEED}
        label="Seed"
        tooltip="Random seed for reproducibility. Use the same seed to get consistent results across runs."
      />
    </div>
  );
};

export default EvolutionaryOptimizerConfigs;
