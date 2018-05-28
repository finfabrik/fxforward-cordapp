package com.finfabrik.corda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.webserver.services.WebServerPluginRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class FXForwardPlugin implements WebServerPluginRegistry {
    private final List<Function<CordaRPCOps, ?>> webApis = ImmutableList.of(FXForwardApi::new);

    @Override
    public List<Function<CordaRPCOps, ?>> getWebApis() {
        return webApis;
    }

    @Override
    public Map<String, String> getStaticServeDirs() {
        return Collections.emptyMap();
    }

    @Override
    public void customizeJSONSerialization(ObjectMapper objectMapper) {
    }
}