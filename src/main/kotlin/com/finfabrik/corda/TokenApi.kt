package com.finfabrik.corda

import com.finfabrik.corda.flows.TokenIssueFlow
import com.finfabrik.corda.flows.TokenTransferFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
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
import javax.ws.rs.*
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

    /** Helpers for filtering the network map cache. */
    private fun isNotary(nodeInfo: NodeInfo) = rpcOps.notaryIdentities().any { nodeInfo.isLegalIdentity(it) }
    private fun isMe(nodeInfo: NodeInfo) = nodeInfo.legalIdentities.first().name == me
    private fun isNetworkMap(nodeInfo : NodeInfo) = nodeInfo.legalIdentities.single().name.organisation == "Network Map Service"

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to myLegalName.toDisplayString())

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<String>> {
        return mapOf("peers" to rpcOps.networkMapSnapshot()
                .filter { isNotary(it).not() && isMe(it).not() && isNetworkMap(it).not() }
                .map { it.legalIdentities.first().name.x500Name.toDisplayString() })
    }

    /**
     * Task 1
     * Displays all Token states that exist in the node's vault.
     * TODO: Return a list of TokenStates on ledger
     * Hint - Use [rpcOps] to query the vault all unconsumed [TokenState]s
     */
    @GET
    @Path("tokens")
    @Produces(MediaType.APPLICATION_JSON)
    fun getTokens(): List<StateAndRef<ContractState>> {
        // Filter by state type: Token.
        return rpcOps.vaultQueryBy<TokenState>().states
    }

    /**
     * Displays all cash states that exist in the node's vault.
     */
    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCash(): List<StateAndRef<ContractState>> {
        // Filter by state type: Cash.
        return rpcOps.vaultQueryBy<Cash.State>().states
    }

    /**
     * Displays all cash states that exist in the node's vault.
     */
    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    // Display cash balances.
    fun getCashBalances() = rpcOps.getCashBalances()

    /**
     * Initiates a flow to agree an Token between two parties.
     */
    @PUT
    @Path("issue-token")
    fun issueToken(@QueryParam(value = "amount") amount: Int,
                   @QueryParam(value = "token") token: String): Response {
        // Get party objects for myself and the counterparty.
        val me = rpcOps.nodeInfo().legalIdentities.first()
        // Create a new Token state using the parameters given.
        try {
            val state = TokenState(Amount(amount.toLong() * 1000, Commodity(token, token)), me)
            // Start the TokenIssueFlow. We block and waits for the flow to return.
            val result = rpcOps.startTrackedFlow(::TokenIssueFlow, state).returnValue.get()
            // Return the response.
            return Response
                    .status(Response.Status.CREATED)
                    .entity("Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single()}")
                    .build()
        // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }

    /**
     * Transfers an Token specified by [linearId] to a new party.
     */
    @GET
    @Path("transfer-token")
    fun transferToken(@QueryParam(value = "id") id: String,
                      @QueryParam(value = "party") party: String): Response {
        val linearId = UniqueIdentifier.fromString(id)
        val newLender = rpcOps.wellKnownPartyFromX500Name(CordaX500Name.parse(party)) ?: throw IllegalArgumentException("Unknown party name.")
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