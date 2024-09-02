package com.comet.opik.domain;

import com.comet.opik.utils.AsyncUtils;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AsyncContextUtils {

    static AsyncUtils.ContextAwareStream<? extends Result> bindWorkspaceIdToFlux(Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        };
    }

    static AsyncUtils.ContextAwareAction<? extends Result> bindWorkspaceIdToMono(Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("workspace_id", workspaceId);
            return Mono.from(statement.execute());
        };
    }

    static AsyncUtils.ContextAwareAction<? extends Result> bindUserNameAndWorkspaceContext(Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("user_name", userName);
            statement.bind("workspace_id", workspaceId);

            return Mono.from(statement.execute());
        };
    }

    static AsyncUtils.ContextAwareStream<? extends Result> bindUserNameAndWorkspaceContextToStream(
            Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("user_name", userName);
            statement.bind("workspace_id", workspaceId);

            return Flux.from(statement.execute());
        };
    }

}
