package com.finfabrik.corda;

import com.finfabrik.corda.flows.IssueFXForward;
import com.finfabrik.corda.flows.SettleFXForward;
import com.google.common.collect.ImmutableMap;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.messaging.FlowHandle;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.OpaqueBytes;
import net.corda.finance.contracts.Commodity;
import net.corda.finance.contracts.Tenor;
import net.corda.finance.contracts.asset.Cash;
import net.corda.finance.flows.AbstractCashFlow;
import net.corda.finance.flows.CashIssueFlow;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CREATED;
import static net.corda.finance.contracts.GetBalances.getCashBalances;

@Path("fxforward")
public class FXForwardApi {
    private final CordaRPCOps rpcOps;
    private final Party myIdentity;

    public FXForwardApi(CordaRPCOps rpcOps) {
        this.rpcOps = rpcOps;
        this.myIdentity = rpcOps.nodeInfo().getLegalIdentities().get(0);
    }

    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Party> me() {
        return ImmutableMap.of("me", myIdentity);
    }

    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, List<String>> peers() {
        return ImmutableMap.of("peers", rpcOps.networkMapSnapshot()
                .stream()
                .filter(nodeInfo -> !nodeInfo.getLegalIdentities().get(0).equals(myIdentity))
                .map(it -> it.getLegalIdentities().get(0).getName().getOrganisation())
                .collect(toList()));
    }

    @GET
    @Path("contracts")
    @Produces(MediaType.APPLICATION_JSON)
    public List<FXForward> forwards() {
        List<StateAndRef<FXForward>> statesAndRefs = rpcOps.vaultQuery(FXForward.class).getStates();

        return statesAndRefs.stream()
                .map(stateAndRef -> stateAndRef.getState().getData())
                .map(state -> {
                    AbstractParty possiblyWellKnownLender = rpcOps.wellKnownPartyFromAnonymous(state.getBuyer());
                    if (possiblyWellKnownLender == null) {
                        possiblyWellKnownLender = state.getBuyer();
                    }

                    AbstractParty possiblyWellKnownBorrower = rpcOps.wellKnownPartyFromAnonymous(state.getSeller());
                    if (possiblyWellKnownBorrower == null) {
                        possiblyWellKnownBorrower = state.getSeller();
                    }

                    return new FXForward(
                            state.getBase(),
                            state.getTerms(),
                            possiblyWellKnownLender,
                            possiblyWellKnownBorrower,
                            state.getTenor(),
                            state.getLinearId());
                })
                .collect(toList());
    }

    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    public List<StateAndRef<Cash.State>> cash() {
        return rpcOps.vaultQuery(Cash.State.class).getStates();
    }

    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<Currency, Amount<Currency>> cashBalances() {
        return getCashBalances(rpcOps);
    }

    @GET
    @Path("issue-currency")
    public Response selfIssueCash(
            @QueryParam(value = "amount") int amount,
            @QueryParam(value = "currency") String currency) {

        final Amount<Currency> issueAmount = new Amount<>((long) amount * 100, Currency.getInstance(currency));
        final List<Party> notaries = rpcOps.notaryIdentities();
        if (notaries.isEmpty()) {
            throw new IllegalStateException("Could not find a notary.");
        }
        final Party notary = notaries.get(0);
        final OpaqueBytes issueRef = OpaqueBytes.of(new byte[1]);
        final CashIssueFlow.IssueRequest issueRequest = new CashIssueFlow.IssueRequest(issueAmount, issueRef, notary);

        try {
            final FlowHandle<AbstractCashFlow.Result> flowHandle = rpcOps.startFlowDynamic(CashIssueFlow.class, issueRequest);
            final AbstractCashFlow.Result result = flowHandle.getReturnValue().get();
            final String msg = result.getStx().getTx().getOutputStates().get(0).toString();
            return Response.status(CREATED).entity(msg).build();
        } catch (Exception e) {
            return Response.status(BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("issue-contract")
    public Response issueForward(
            @QueryParam(value = "base") int base,
            @QueryParam(value = "currency") String currency,
            @QueryParam(value = "terms") int terms,
            @QueryParam(value = "token") String token,
            @QueryParam(value = "party") String party,
            @QueryParam(value = "tenor") String tenorStr) {

        final Set<Party> lenderIdentities = rpcOps.partiesFromName(party, false);
        if (lenderIdentities.size() != 1) {
            final String errMsg = String.format("Found %d identities for the lender.", lenderIdentities.size());
            throw new IllegalStateException(errMsg);
        }
        final Party buyer = lenderIdentities.iterator().next();

      final Amount currencyAmt = new Amount<>((long) base * 100, Currency.getInstance(currency));
      final Amount tokenAmt = new Amount<>((long)terms, new Commodity(token, token, 0));

        Tenor tenor = new Tenor(tenorStr);
        try {
            final FlowHandle<SignedTransaction> flowHandle = rpcOps.startFlowDynamic(
                    IssueFXForward.Initiator.class,
                currencyAmt, tokenAmt, buyer, tenor, true
            );

            final SignedTransaction result = flowHandle.getReturnValue().get();
            final String msg = String.format("Transaction id %s committed to ledger.\n%s",
                    result.getId(), result.getTx().getOutputStates().get(0));
            return Response.status(CREATED).entity(msg).build();
        } catch (Exception e) {
            return Response.status(BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("settle-contract")
    public Response settleForward(
            @QueryParam(value = "contractId") String contractId,
            @QueryParam(value = "tokenId") String tokenId
            ) {
        UniqueIdentifier contract = UniqueIdentifier.Companion.fromString(contractId);
        UniqueIdentifier token = UniqueIdentifier.Companion.fromString(tokenId);

        try {
            final FlowHandle flowHandle = rpcOps.startFlowDynamic(
                    SettleFXForward.Initiator.class,
                contract, token, true);

            flowHandle.getReturnValue().get();
            final String msg = String.format("forward %s settled with token %s", contractId, tokenId);
            return Response.status(CREATED).entity(msg).build();
        } catch (Exception e) {
            return Response.status(BAD_REQUEST).entity(e.getMessage()).build();
        }
    }
}