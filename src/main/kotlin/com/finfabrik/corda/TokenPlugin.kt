package com.finfabrik.corda

import net.corda.core.messaging.CordaRPCOps
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function

class TokenPlugin : WebServerPluginRegistry {

    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TokenApi))

    override val staticServeDirs: Map<String, String> = emptyMap();
}