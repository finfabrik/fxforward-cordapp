package com.finfabrik.corda.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.finfabrik.corda.FXForward;
import com.finfabrik.corda.FXForwardContract;
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
import com.finfabrik.corda.flows.FXForwardBaseFlow.SignTxFlowNoChecking;
import net.corda.finance.contracts.Commodity;
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
        private final Amount<Currency> base;
        private final Amount<Commodity> terms;
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

        public Initiator(UniqueIdentifier linearId, Amount<Currency> base, Amount<Commodity> terms, Boolean anonymous) {
            this.linearId = linearId;
            this.base = base;
            this.terms = terms;
            this.anonymous = anonymous;
        }

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            // Stage 1. Retrieve obligation specified by linearId from the vault.
            progressTracker.setCurrentStep(PREPARATION);
            final StateAndRef<FXForward> obligationToSettle = getObligationByLinearId(linearId);
            final FXForward inputFXForward = obligationToSettle.getState().getData();

            // Stage 2. Resolve the lender and borrower identity if the obligation is anonymous.
            final Party borrowerIdentity = resolveIdentity(inputFXForward.getSeller());
            final Party lenderIdentity = resolveIdentity(inputFXForward.getBuyer());

            // Stage 3. This flow can only be initiated by the current recipient.
            if (!borrowerIdentity.equals(getOurIdentity())) {
                throw new FlowException("Settle FXForward flow must be initiated by the borrower.");
            }

            // Stage 4. Check we have enough cash to settle the requested base.
            final Amount<Currency> cashBalance = getCashBalance(getServiceHub(), base.getToken());
            final Amount<Currency> amountLeftToSettle = inputFXForward.getBase();
            if (cashBalance.getQuantity() <= 0L) {
                throw new FlowException(String.format("Borrower has no %s to settle.", base.getToken()));
            } else if (cashBalance.getQuantity() < base.getQuantity()) {
                throw new FlowException(String.format(
                        "Borrower has only %s but needs %s to settle.", cashBalance, base));
            } else if (amountLeftToSettle.getQuantity() < base.getQuantity()) {
                throw new FlowException(String.format(
                        "There's only %s left to settle but you pledged %s.", amountLeftToSettle, base));
            }

            // Stage 5. Create a settle command.
            final List<PublicKey> requiredSigners = inputFXForward.getParticipantKeys();
            final Command settleCommand = new Command<>(new FXForwardContract.Commands.Settle(), requiredSigners);

            // Stage 6. Create a transaction builder. Add the settle command and input obligation.
            progressTracker.setCurrentStep(BUILDING);
            final TransactionBuilder builder = new TransactionBuilder(getFirstNotary())
                    .addInputState(obligationToSettle)
                    .addCommand(settleCommand);

            // Stage 7. Get some cash from the vault and add a spend to our transaction builder.
            final List<PublicKey> cashSigningKeys = Cash.generateSpend(
                    getServiceHub(),
                    builder,
                base,
                    inputFXForward.getBuyer(),
                    ImmutableSet.of()).getSecond();

            // Stage 8. Only add an output obligation state if the obligation has not been fully settled.

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
            final FlowSession session = initiateFlow(lenderIdentity);
            subFlow(new IdentitySyncFlow.Send(session, ptx.getTx()));
            final SignedTransaction stx = subFlow(new CollectSignaturesFlow(
                    ptx,
                    ImmutableSet.of(session),
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