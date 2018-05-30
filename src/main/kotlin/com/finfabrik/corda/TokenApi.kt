package com.finfabrik.corda

import com.finfabrik.corda.flows.TokenIssueFlow
import com.finfabrik.corda.flows.TokenTransferFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.internal.x500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.NodeInfo
import net.corda.core.utilities.loggerFor
import net.corda.finance.contracts.Commodity
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.slf4j.Logger
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response


@Path("token")
class TokenApi(val rpcOps: CordaRPCOps) {
    private val me = rpcOps.nodeInfo().legalIdentities.first().name
    private val myLegalName = me.x500Name

    companion object {
        private val logger: Logger = loggerFor<TokenApi>()
    }

    fun X500Name.toDisplayString() : String  = BCStyle.INSTANCE.toString(this)

    private fun isNotary(nodeInfo: NodeInfo) = rpcOps.notaryIdentities().any { nodeInfo.isLegalIdentity(it) }
    private fun isMe(nodeInfo: NodeInfo) = nodeInfo.legalIdentities.first().name == me
    private fun isNetworkMap(nodeInfo : NodeInfo) = nodeInfo.legalIdentities.single().name.organisation == "Network Map Service"

    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to myLegalName.toDisplayString())

    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<String>> {
        return mapOf("peers" to rpcOps.networkMapSnapshot()
                .filter { isNotary(it).not() && isMe(it).not() && isNetworkMap(it).not() }
                .map { it.legalIdentities.first().name.x500Name.toDisplayString() })
    }

    @GET
    @Path("tokens")
    @Produces(MediaType.APPLICATION_JSON)
    fun getTokens(): List<StateAndRef<ContractState>> {
        return rpcOps.vaultQueryBy<Token>().states
    }

    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCash(): List<StateAndRef<ContractState>> {
        return rpcOps.vaultQueryBy<Cash.State>().states
    }

    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCashBalances() = rpcOps.getCashBalances()

    @GET
    @Path("issue-token")
    fun issueToken(@QueryParam(value = "amount") amount: Int,
                   @QueryParam(value = "token") token: String): Response {
        val me = rpcOps.nodeInfo().legalIdentities.first()
        try {
            val state = Token(Amount(amount.toLong(), Commodity(token, token)), me)
            val result = rpcOps.startTrackedFlow(::TokenIssueFlow, state).returnValue.get()
            return Response
                    .status(Response.Status.CREATED)
                    .entity("Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single()}")
                    .build()
        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }

    @GET
    @Path("transfer-token")
    fun transferToken(@QueryParam(value = "id") id: String,
                      @QueryParam(value = "party") party: String): Response {
        val linearId = UniqueIdentifier.fromString(id)
        val lenderIdentities = rpcOps.partiesFromName(party, false)
        if (lenderIdentities.size != 1) {
            val errMsg = String.format("Found %d identities for the new lender.", lenderIdentities.size)
            throw IllegalStateException(errMsg)
        }
        val newLender = lenderIdentities.iterator().next()
        try {
            rpcOps.startFlow(::TokenTransferFlow, linearId, newLender).returnValue.get()
            return Response.status(Response.Status.CREATED).entity("Token $id transferred to $party.").build()

        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }
}