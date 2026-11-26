--liquibase formatted sql
--changeset miguelg:000126_convert_annotation_queue_router_conditions_to_filter_groups
--comment: Store annotation queue automation as filter groups in automation_rules.filters (OPIK-8842)

-- Queue automation now matches on the same trace and thread filters as the traces and threads pages, as OR-ed
-- groups of AND-ed filters, kept in the rule's shared filters column. Each stored condition group becomes a
-- filter group and each condition a feedback_scores filter on the same score name, operator and threshold, so the
-- OR between groups and the AND within them carry over unchanged. Thresholds are written as plain decimals with
-- trailing zeros dropped (0.5, 1, 0), the form a filter's string value takes. Groups with no conditions are
-- dropped, and an automation left with none keeps NULL. Evaluator rules are not touched.
--
-- The conditions column stays as it is, so the previous release still reads every router after a rollback: migrated
-- routers keep their conditions, and new ones are written with no condition groups, which match nothing there. A
-- later migration drops the column.
UPDATE automation_rules rule
JOIN (
    SELECT grouped.id, JSON_ARRAYAGG(grouped.filters) AS filter_groups
    FROM (
        SELECT conditions.id, conditions.group_index,
               JSON_ARRAYAGG(JSON_OBJECT(
                       'field', 'feedback_scores',
                       'operator', conditions.operator,
                       'key', conditions.score_name,
                       'value', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(conditions.threshold AS CHAR)))
               )) AS filters
        FROM (
            SELECT router.id, c.group_index, c.score_name, c.operator, c.threshold
            FROM automation_rule_annotation_queue_routers router,
                 JSON_TABLE(router.conditions, '$.groups[*]' COLUMNS (
                         group_index FOR ORDINALITY,
                         NESTED PATH '$.conditions[*]' COLUMNS (
                                 score_name VARCHAR(255) PATH '$.score_name',
                                 operator VARCHAR(8) PATH '$.operator',
                                 threshold DECIMAL(38, 9) PATH '$.value'
                         )
                 )) c
            WHERE c.score_name IS NOT NULL
        ) conditions
        GROUP BY conditions.id, conditions.group_index
    ) grouped
    GROUP BY grouped.id
) converted ON converted.id = rule.id
SET rule.filters = converted.filter_groups
WHERE rule.action = 'annotation_queue_router'
  AND rule.filters IS NULL;

--rollback empty
