package com.comet.opik.domain;

import java.util.List;

/**
 * Names of demo entities created by current and past demo-data generation code. These names are excluded from
 * V1-entity checks so that leftover demo data does not block workspace V2 promotion.
 *
 * <p><b>Never remove entries from these lists</b>, even after the demo generation code that created
 * them has been deleted. The data still exists in production databases and removing an entry here
 * would silently re-block affected workspaces.
 *
 * <p>ClickHouse queries (experiments, optimizations) are case-sensitive — list every known casing.
 * MySQL queries (datasets, prompts) use utf8mb4_unicode_ci collation so matching is already
 * case-insensitive.
 */
public class DemoData {

    public static final List<String> PROJECTS = List.of("Demo evaluation", "Demo chatbot 🤖",
            "Opik Demo Agent Observability", "Opik Demo Assistant", "Opik Demo Optimizer");

    /** MySQL (utf8mb4_unicode_ci) — matching is case-insensitive, no need for case variants. */
    public static final List<String> DATASETS = List.of("Demo dataset", "Opik Demo Questions",
            "Demo - Opik Chatbot", "Demo - Jailbreak Password", "Demo - Customer Message Classifier");

    /** ClickHouse — matching is case-sensitive, list every known casing explicitly. */
    public static final List<String> EXPERIMENTS = List.of(
            "Demo evaluation",
            "Demo experiment",
            "Demo Experiment",
            "Demo-opik-assistant-v2",
            "Demo-opik-assistant-v1",

            "Demo-forward_tamarind_3921",
            "Demo-colorful_cobbler_5082",
            "Demo-profound_grouse_5114",
            "Demo-normal_toucan_3742",
            "Demo-flexible_tundra_8981",
            "Demo-specified_resin_1121",
            "Demo-coral_chateau_1249",
            "Demo-major_fruit_6938",
            "Demo-flat_bulb_3765",
            "Demo-horizontal_shrimp_833",
            "Demo-continuous_milk_2919");

    /** MySQL (utf8mb4_unicode_ci) — matching is case-insensitive, no need for case variants. */
    public static final List<String> PROMPTS = List.of(
            "Demo - Opik SDK Assistant - System Prompt",
            "Q&A Prompt");

    /** ClickHouse — matching is case-sensitive, list every known casing explicitly. */
    public static final List<String> OPTIMIZATIONS = List.of("ivory_berm_3833");
}
