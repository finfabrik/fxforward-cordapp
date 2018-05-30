package com.finfabrik.corda.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.finfabrik.corda.FXForward;
import com.finfabrik.corda.FXForwardContract;
import com.finfabrik.corda.flows.FXForwardBaseFlow.SignTxFlowNoChecking;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.corda.confidential.IdentitySyncFlow;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import net.corda.finance.contracts.asset.Cash;

import java.security.PublicKey;
import java.util.Currency;
import java.util.List;

import static net.corda.finance.contracts.GetBalances.getCashBalance;

public class SettleFXForward {
    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends FXForwardBaseFlow {
        private final UniqueIdentifier linearId;
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

        public Initiator(UniqueIdentifier linearId, Boolean anonymous) {
            this.linearId = linearId;
            this.anonymous = anonymous;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // Stage 1. Retrieve forward specified by linearId from the vault.
            progressTracker.setCurrentStep(PREPARATION);
            final StateAndRef<FXForward> contractToSettle = getForwardByLinearId(linearId);
            final FXForward inputFXForward = contractToSettle.getState().getData();

            // Stage 2. Resolve the seller and buyer identity if the contract is anonymous.
            final Party sellerIdentity = resolveIdentity(inputFXForward.getSeller());
            final Party buyerIdentity = resolveIdentity(inputFXForward.getBuyer());

            // Stage 3. This flow can only be initiated by the current recipient.
            if (!sellerIdentity.equals(getOurIdentity())) {
                throw new FlowException("Settle FXForward flow must be initiated by the seller.");
            }

            // Stage 4. Check we have enough cash to settle the requested base.
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

            // Stage 5. Create a settle command.
            final List<PublicKey> requiredSigners = inputFXForward.getParticipantKeys();
            final Command settleCommand = new Command<>(new FXForwardContract.Commands.Settle(), requiredSigners);

            // Stage 6. Create a transaction builder. Add the settle command and input forward.
            progressTracker.setCurrentStep(BUILDING);
            final TransactionBuilder builder = new TransactionBuilder(getFirstNotary())
                    .addInputState(contractToSettle)
                    .addCommand(settleCommand);

            // Stage 7. Get some cash from the vault and add a spend to our transaction builder.
            final List<PublicKey> cashSigningKeys = Cash.generateSpend(
                    getServiceHub(),
                    builder,
                    cashToSettle,
                    inputFXForward.getBuyer(),
                    ImmutableSet.of()).getSecond();

            // Stage 8. Only add an output forward state if the forward has not been fully settled.

            // Stage 9. Verify and sign the transaction.
            progressTracker.setCurrentStep(SIGNING);
            builder.verify(getServiceHub());
            final List<PublicKey> signingKeys = new ImmutableList.Builder<PublicKey>()
                    .addAll(cashSigningKeys)
                    .add(inputFXForward.getSeller().getOwningKey())
                    .build();
            final SignedTransaction ptx = getServiceHub().signInitialTransaction(builder, signingKeys);

            // Stage 10. Get counterparty signature.
            progressTracker.setCurrentStep(COLLECTING);
            subFlow(new IdentitySyncFlow.Send(buyerSession, ptx.getTx()));
            final SignedTransaction stx = subFlow(new CollectSignaturesFlow(
                    ptx,
                    ImmutableSet.of(buyerSession),
                    signingKeys,
                    COLLECTING.childProgressTracker()));

            // Stage 11. Finalize the transaction.
            progressTracker.setCurrentStep(FINALISING);
            return subFlow(new FinalityFlow(stx, FINALISING.childProgressTracker()));
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Responder extends FlowLogic<SignedTransaction> {
        private final FlowSession otherFlow;

        public Responder(FlowSession otherFlow) {
            this.otherFlow = otherFlow;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {


            subFlow(new IdentitySyncFlow.Receive(otherFlow));
            SignedTransaction stx = subFlow(new SignTxFlowNoChecking(otherFlow, SignTransactionFlow.Companion.tracker()));
            return waitForLedgerCommit(stx.getId());
        }
    }
}