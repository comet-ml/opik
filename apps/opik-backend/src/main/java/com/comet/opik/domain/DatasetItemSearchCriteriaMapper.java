package com.comet.opik.domain;

import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import io.r2dbc.spi.Statement;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;

import java.util.List;

/**
 * Utility class for applying {@link DatasetItemSearchCriteria} filters and search to
 * StringTemplates and R2DBC statements.
 * Centralizes the wiring to avoid duplication between {@link DatasetItemVersionDAO} and
 * {@link com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesDAO}.
 */
public final class DatasetItemSearchCriteriaMapper {

    private DatasetItemSearchCriteriaMapper() {
    }

    /**
     * Applies filter strategies and the search flag to a StringTemplate.
     *
     * @param template       the ST template to populate
     * @param criteria       the search criteria containing filters and search text
     * @param strategyParams ordered list of (strategy, templateParam) pairs to evaluate
     */
    public static void applyToTemplate(ST template, DatasetItemSearchCriteria criteria,
            List<FilterQueryBuilder.FilterStrategyParam> strategyParams) {
        FilterQueryBuilder.applyFiltersToTemplate(template, criteria.filters(), strategyParams);
        if (StringUtils.isNotBlank(criteria.search())) {
            template.add("search", true);
        }
    }

    /**
     * Binds filter strategy parameters and search terms to an R2DBC statement.
     *
     * @param statement          the statement to bind to
     * @param criteria           the search criteria containing filters and search text
     * @param strategies         ordered list of filter strategies to bind
     * @param filterQueryBuilder the filter query builder for binding filter and search parameters
     * @return the statement with all parameters bound
     */
    public static Statement bindSearchCriteria(Statement statement, DatasetItemSearchCriteria criteria,
            List<FilterStrategy> strategies, FilterQueryBuilder filterQueryBuilder) {
        FilterQueryBuilder.bindFilters(statement, criteria.filters(), strategies);
        if (StringUtils.isNotBlank(criteria.search())) {
            filterQueryBuilder.bindSearchTerms(statement, criteria.search());
        }
        return statement;
    }
}
