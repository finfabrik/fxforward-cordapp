package com.finfabrik.corda.flows;

import com.finfabrik.corda.FXForward;
import com.finfabrik.corda.Token;
import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.FlowSession;
import net.corda.core.flows.SignTransactionFlow;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.ProgressTracker;

import java.util.List;

abstract class FXForwardBaseFlow extends FlowLogic<SignedTransaction> {

    Party getFirstNotary() throws FlowException {
        List<Party> notaries = getServiceHub().getNetworkMapCache().getNotaryIdentities();
        if (notaries.isEmpty()) {
            throw new FlowException("No available notary.");
        }
        return notaries.get(0);
    }

    StateAndRef<FXForward> getForwardByLinearId(UniqueIdentifier contractId) throws FlowException {
        QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(
                null,
                ImmutableList.of(contractId),
                Vault.StateStatus.UNCONSUMED,
                null);

        List<StateAndRef<FXForward>> forwards = getServiceHub().getVaultService().queryBy(FXForward.class, queryCriteria).getStates();
        if (forwards.size() != 1) {
            throw new FlowException(String.format("FXForward with id %s not found.", contractId));
        }
        return forwards.get(0);
    }

    StateAndRef<Token> getTokenByLinearId(UniqueIdentifier tokenId) throws FlowException {
        QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(
            null,
            ImmutableList.of(tokenId),
            Vault.StateStatus.UNCONSUMED,
            null);

        List<StateAndRef<Token>> tokens = getServiceHub().getVaultService().queryBy(Token.class, queryCriteria).getStates();
        if (tokens.size() != 1) {
            throw new FlowException(String.format("Token with id %s not found.", tokenId));
        }
        return tokens.get(0);
    }

    Party resolveIdentity(AbstractParty abstractParty) {
        return getServiceHub().getIdentityService().requireWellKnownPartyFromAnonymous(abstractParty);
    }

    static class SignTxFlowNoChecking extends SignTransactionFlow {
        SignTxFlowNoChecking(FlowSession otherFlow, ProgressTracker progressTracker) {
            super(otherFlow, progressTracker);
        }

        @Override
        protected void checkTransaction(SignedTransaction tx) {
            // TODO: Add checking here.
        }
    }
}