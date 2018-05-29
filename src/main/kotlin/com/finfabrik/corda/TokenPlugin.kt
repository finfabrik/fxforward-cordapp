package com.finfabrik.corda

import net.corda.core.messaging.CordaRPCOps
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function

class TokenPlugin : WebServerPluginRegistry {
    /**
     * A list of classes that expose web APIs.
     */
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TokenApi))

    /**
     * A list of directories in the resources directory that will be served by Jetty under /web.
     * The template's web frontend is accessible at /web/template.
     */
    override val staticServeDirs: Map<String, String> = emptyMap();
}