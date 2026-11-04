package com.comet.opik.domain;

import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.infrastructure.db.GuardrailExecutionScopeMapper;
import com.comet.opik.infrastructure.db.GuardrailPolicyRulesColumnMapper;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterArgumentFactory(GuardrailExecutionScopeMapper.class)
@RegisterColumnMapper(GuardrailExecutionScopeMapper.class)
@RegisterColumnMapper(GuardrailPolicyRulesColumnMapper.class)
@RegisterConstructorMapper(GuardrailPolicy.class)
public interface GuardrailPolicyDAO {

    String SELECT_POLICIES = """
            SELECT p.id, p.name, p.description, p.execution_scope, p.created_at, p.created_by, p.last_updated_at,
                p.last_updated_by,
                (SELECT JSON_ARRAYAGG(JSON_OBJECT('type', r.type, 'config', r.config))
                    FROM guardrail_policy_rules r WHERE r.policy_id = p.id) AS rules
            FROM guardrail_policies p
            """;

    @SqlUpdate("""
            INSERT INTO guardrail_policies(id, workspace_id, name, description, execution_scope, created_by,
                last_updated_by)
            VALUES (:policy.id, :workspaceId, :policy.name, :policy.description, :policy.executionScope, :userName,
                :userName)
            """)
    void save(@BindMethods("policy") GuardrailPolicy policy, @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlBatch("""
            INSERT INTO guardrail_policy_rules(id, policy_id, type, config, created_by, last_updated_by)
            VALUES (:id, :policyId, :type, :config, :userName, :userName)
            """)
    void saveRules(@Bind("id") List<UUID> ids, @Bind("policyId") UUID policyId,
            @Bind("type") List<GuardrailType> types, @Bind("config") List<JsonNode> configs,
            @Bind("userName") String userName);

    // last_updated_at is set explicitly: a change to the rules alone leaves this row as it was, and MySQL only
    // refreshes ON UPDATE columns when a value changes.
    @SqlUpdate("""
            UPDATE guardrail_policies SET name = :policy.name, description = :policy.description,
                execution_scope = :policy.executionScope, last_updated_at = CURRENT_TIMESTAMP(6),
                last_updated_by = :userName
            WHERE id = :policy.id AND workspace_id = :workspaceId
            """)
    int update(@BindMethods("policy") GuardrailPolicy policy, @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlQuery(SELECT_POLICIES + "WHERE p.workspace_id = :workspaceId AND p.id = :id")
    Optional<GuardrailPolicy> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    // Exact names and the scope each have an index of their own (see migration 000112).
    String FILTERS = """
            <if(name)> AND name LIKE CONCAT('%', :name, '%') <endif>
            <if(names)> AND name IN (<names>) <endif>
            <if(execution_scope)> AND execution_scope = :executionScope <endif>
            """;

    @SqlQuery("SELECT COUNT(*) FROM guardrail_policies WHERE workspace_id = :workspaceId " + FILTERS)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Bind("workspaceId") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("names") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "names") Set<String> names,
            @Define("execution_scope") @Bind("executionScope") GuardrailExecutionScope executionScope);

    /**
     * The ids of one page, in order. Selects only {@code id} so the rules JSON never enters a sort buffer, as in
     * the two-step dashboard page fetch (OPIK-6482); {@link #findByIds(Collection, String)} loads the bodies.
     */
    @SqlQuery("SELECT id FROM guardrail_policies WHERE workspace_id = :workspaceId " + FILTERS + """
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<UUID> findPageIds(@Bind("workspaceId") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("names") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "names") Set<String> names,
            @Define("execution_scope") @Bind("executionScope") GuardrailExecutionScope executionScope,
            @Define("sort_fields") String sortFields, @Bind("limit") int limit, @Bind("offset") int offset);

    @SqlQuery(SELECT_POLICIES + "WHERE p.workspace_id = :workspaceId AND p.id IN (<ids>)")
    @UseStringTemplateEngine
    List<GuardrailPolicy> findByIds(@BindList("ids") Collection<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM guardrail_policy_rules WHERE policy_id IN (<policyIds>)")
    @UseStringTemplateEngine
    void deleteRules(@BindList("policyIds") Collection<UUID> policyIds);

    @SqlUpdate("DELETE FROM guardrail_policies WHERE workspace_id = :workspaceId AND id = :id")
    int delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);
}
