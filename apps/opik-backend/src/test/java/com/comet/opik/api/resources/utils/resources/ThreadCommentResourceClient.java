package com.comet.opik.api.resources.utils.resources;

import ru.vyarus.dropwizard.guice.test.ClientSupport;

public class ThreadCommentResourceClient extends BaseCommentResourceClient {

    public ThreadCommentResourceClient(ClientSupport client, String baseURI) {
        super("%s/v1/private/traces/threads", client, baseURI);
    }
}
