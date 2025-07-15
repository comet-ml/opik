package com.comet.opik.domain;

import com.comet.opik.api.Tag;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterRowMapper(TagMapper.class)
public interface TagDAO {

    @SqlQuery("SELECT * FROM tags WHERE workspace_id = :workspaceId ORDER BY name")
    List<Tag> findByWorkspaceId(@Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM tags WHERE id = :id AND workspace_id = :workspaceId")
    Optional<Tag> findByIdAndWorkspaceId(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM tags WHERE name = :name AND workspace_id = :workspaceId")
    Optional<Tag> findByNameAndWorkspaceId(@Bind("name") String name, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM tags WHERE workspace_id = :workspaceId AND name LIKE CONCAT('%', :searchTerm, '%') ORDER BY name")
    List<Tag> searchByWorkspaceId(@Bind("workspaceId") String workspaceId, @Bind("searchTerm") String searchTerm);

    @SqlUpdate("INSERT INTO tags (id, name, description, workspace_id) VALUES (:id, :name, :description, :workspaceId)")
    void insert(@BindBean Tag tag);

    @SqlUpdate("UPDATE tags SET name = :name, description = :description WHERE id = :id AND workspace_id = :workspaceId")
    int update(@Bind("id") UUID id, @Bind("name") String name, @Bind("description") String description,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM tags WHERE id = :id AND workspace_id = :workspaceId")
    int deleteByIdAndWorkspaceId(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM tags WHERE workspace_id = :workspaceId")
    long countByWorkspaceId(@Bind("workspaceId") String workspaceId);
}