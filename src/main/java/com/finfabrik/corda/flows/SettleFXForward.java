package com.finfabrik.corda.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.finfabrik.corda.FXForward;
import com.finfabrik.corda.FXForwardContract;
import com.finfabrik.corda.Token;
import com.finfabrik.corda.TokenContract;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.corda.confidential.IdentitySyncFlow;
import net.corda.core.contracts.*;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import net.corda.core.utilities.UntrustworthyData;
import net.corda.finance.contracts.asset.Cash;

import java.security.PublicKey;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

import static net.corda.finance.contracts.GetBalances.getCashBalance;

public class SettleFXForward {
    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends FXForwardBaseFlow {
        private final UniqueIdentifier contractId;
        private final UniqueIdentifier tokenId;
        private final Boolean anonymous;

        private final Step PREPARATION = new Step("Obtaining IOU from vault.");
        private final Step BUILDING = new Step("Building and verifying transaction.");
        private final Step SIGNING = new Step("Signing transaction.");
        private final Step COLLECTING = new Step("Collecting counterparty signature.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final Step FINALISING = new Step("Finalising transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(
                PREPARATION, BUILDING, SIGNING, COLLECTING, FINALISING
        );

        public Initiator(UniqueIdentifier contractId, UniqueIdentifier tokenId, Boolean anonymous) {
            this.contractId = contractId;
            this.tokenId = tokenId;
            this.anonymous = anonymous;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {

            progressTracker.setCurrentStep(PREPARATION);
            final StateAndRef<FXForward> contractToSettle = getForwardByLinearId(contractId);
            final FXForward inputFXForward = contractToSettle.getState().getData();

            final Party sellerIdentity = resolveIdentity(inputFXForward.getSeller());
            final Party buyerIdentity = resolveIdentity(inputFXForward.getBuyer());

            if (!sellerIdentity.equals(getOurIdentity())) {
                throw new FlowException("Settle FXForward flow must be initiated by the seller.");
            }

            Amount<Currency> cashToSettle = inputFXForward.getBase();
            final Amount<Currency> cashBalance = getCashBalance(getServiceHub(), cashToSettle.getToken());
            if (cashBalance.getQuantity() <= 0L) {
                throw new FlowException(String.format("Seller has no %s to settle.", cashToSettle.getToken()));
            } else if (cashBalance.getQuantity() < cashToSettle.getQuantity()) {
                throw new FlowException(String.format(
                        "Seller has only %s but needs %s to settle.", cashBalance, cashToSettle));
            } else if (cashToSettle.getQuantity() < cashToSettle.getQuantity()) {
                throw new FlowException(String.format(
                        "There's only %s left to settle but you pledged %s.", cashToSettle, cashToSettle));
            }

            FlowSession buyerSession = initiateFlow(buyerIdentity);
            buyerSession.send(tokenId);
            List<StateAndRef<Token>> stateAndRefs = subFlow(new ReceiveStateAndRefFlow<Token>(buyerSession));

            StateAndRef<Token> tokenInput = stateAndRefs.get(0);
            Token token = tokenInput.getState().getData();
            Token ourToken = token.withNewOwner(sellerIdentity);

            final List<PublicKey> requiredSigners = inputFXForward.getParticipantKeys();
            final Command settleCommand = new Command<>(new FXForwardContract.Commands.Settle(), requiredSigners);

            final Command<TokenContract.Commands.Transfer> transferCommand = new Command<>(new TokenContract.Commands.Transfer(), requiredSigners);

            progressTracker.setCurrentStep(BUILDING);
            final TransactionBuilder builder = new TransactionBuilder(getFirstNotary())
                    .addInputState(tokenInput)
                    .addCommand(transferCommand)
                    .addOutputState(ourToken, TokenContract.Companion.getToken_CONTRACT_ID())
                    .addInputState(contractToSettle)
                    .addCommand(settleCommand);

            final List<PublicKey> cashSigningKeys = Cash.generateSpend(
                    getServiceHub(),
                    builder,
                    cashToSettle,
                    inputFXForward.getBuyer(),
                    ImmutableSet.of()).getSecond();

            progressTracker.setCurrentStep(SIGNING);
            builder.verify(getServiceHub());
            final List<PublicKey> signingKeys = new ImmutableList.Builder<PublicKey>()
                    .addAll(cashSigningKeys)
                    .add(inputFXForward.getSeller().getOwningKey())
                    .build();
            final SignedTransaction ptx = getServiceHub().signInitialTransaction(builder, signingKeys);

            progressTracker.setCurrentStep(COLLECTING);
            subFlow(new IdentitySyncFlow.Send(buyerSession, ptx.getTx()));
            final SignedTransaction stx = subFlow(new CollectSignaturesFlow(
                    ptx,
                    ImmutableSet.of(buyerSession),
                    signingKeys,
                    COLLECTING.childProgressTracker()));

            progressTracker.setCurrentStep(FINALISING);
            return subFlow(new FinalityFlow(stx, FINALISING.childProgressTracker()));
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Responder extends FXForwardBaseFlow {
        private final FlowSession sourceFlow;

        public Responder(FlowSession sourceFlow) {
            this.sourceFlow = sourceFlow;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {

            UntrustworthyData<UniqueIdentifier> tokenId = sourceFlow.receive(UniqueIdentifier.class);
            final StateAndRef<Token> token = getTokenByLinearId(tokenId.unwrap((UntrustworthyData.Validator<UniqueIdentifier, UniqueIdentifier>) data -> data));
            subFlow(new SendStateAndRefFlow(sourceFlow, Collections.singletonList(token)));
            subFlow(new IdentitySyncFlow.Receive(sourceFlow));
            SignedTransaction stx = subFlow(new SignTxFlowNoChecking(sourceFlow, SignTransactionFlow.Companion.tracker()));
            return waitForLedgerCommit(stx.getId());
        }
    }
}