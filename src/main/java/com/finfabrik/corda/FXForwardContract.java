package com.finfabrik.corda;

import net.corda.core.contracts.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.transactions.LedgerTransaction;
import net.corda.finance.contracts.asset.Cash;

import java.security.PublicKey;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;
import static net.corda.core.contracts.ContractsDSL.requireSingleCommand;
import static net.corda.core.contracts.ContractsDSL.requireThat;
import static net.corda.core.contracts.Structures.withoutIssuer;
import static net.corda.finance.utils.StateSumming.sumCash;

public class FXForwardContract implements Contract {
    public static final String OBLIGATION_CONTRACT_ID = "com.finfabrik.corda.FXForwardContract";

    public interface Commands extends CommandData {
        class Issue extends TypeOnlyCommandData implements Commands {
        }
        class Settle extends TypeOnlyCommandData implements Commands {
        }
    }

    @Override
    public void verify(LedgerTransaction tx) {
        final CommandWithParties<Commands> command = requireSingleCommand(tx.getCommands(), Commands.class);
        final Commands commandData = command.getValue();
        final Set<PublicKey> setOfSigners = new HashSet<>(command.getSigners());
        if (commandData instanceof Commands.Issue) {
            verifyIssue(tx, setOfSigners);
        } else if (commandData instanceof Commands.Settle) {
            verifySettle(tx, setOfSigners);
        } else {
            throw new IllegalArgumentException("Unrecognised command.");
        }
    }

    private Set<PublicKey> keysFromParticipants(FXForward FXForward) {
        return FXForward
                .getParticipants().stream()
                .map(AbstractParty::getOwningKey)
                .collect(toSet());
    }

    // This only allows one obligation issuance per transaction.
    private void verifyIssue(LedgerTransaction tx, Set<PublicKey> signers) {
        requireThat(req -> {
            req.using("No inputs should be consumed when issuing an FXForward.",
                    tx.getInputStates().isEmpty());
            req.using("Only one FXForward state should be created when issuing an FXForward.", tx.getOutputStates().size() == 1);
            FXForward FXForward = (FXForward) tx.getOutputStates().get(0);
            req.using("A newly issued FXForward must have a positive amount.", FXForward.getTerms().getQuantity() > 0);
            req.using("The lender and borrower cannot be the same identity.", !FXForward.getSeller().equals(FXForward.getBuyer()));
            req.using("Both lender and borrower together only may sign FXForward issue transaction.",
                    signers.equals(keysFromParticipants(FXForward)));
            return null;
        });
    }

    private void verifySettle(LedgerTransaction tx, Set<PublicKey> signers) {
        requireThat(req -> {
            // Check for the presence of an input obligation state.
            List<FXForward> FXForwardInputs = tx.inputsOfType(FXForward.class);
            req.using("There must be one input obligation.", FXForwardInputs.size() == 1);

            // Check there are output cash states.
            // We don't care about cash inputs, the Cash contract handles those.
            List<Cash.State> cash = tx.outputsOfType(Cash.State.class);
            req.using("There must be output cash.", !cash.isEmpty());

            // Check that the cash is being assigned to us.
            FXForward inputFXForward = FXForwardInputs.get(0);
            List<Cash.State> acceptableCash = cash.stream().filter(it -> it.getOwner().equals(inputFXForward.getBuyer())).collect(Collectors.toList());
            req.using("There must be output cash paid to the recipient.", !acceptableCash.isEmpty());

            // Sum the cash being sent to us (we don't care about the issuer).
            Amount<Currency> sumAcceptableCash = withoutIssuer(sumCash(acceptableCash));
            Amount<Currency> amountOutstanding = inputFXForward.getBase();
            req.using("The amount settled cannot be more than the amount outstanding.", amountOutstanding.compareTo(sumAcceptableCash) >= 0);

            List<FXForward> FXForwardOutputs = tx.outputsOfType(FXForward.class);

            // Check to see if we need an output obligation or not.
            if (amountOutstanding.equals(sumAcceptableCash)) {
                // If the obligation has been fully settled then there should be no obligation output state.
                req.using("There must be no output obligation as it has been fully settled.", FXForwardOutputs.isEmpty());
            } else {
                // If the obligation has been partially settled then it should still exist.
                req.using("There must be one output obligation.", FXForwardOutputs.size() == 1);

                // Check only the paid property changes.
                FXForward outputFXForward = FXForwardOutputs.get(0);
                req.using("The amount may not change when settling.", inputFXForward.getTerms().equals(outputFXForward.getTerms()));
                req.using("The borrower may not change when settling.", inputFXForward.getSeller().equals(outputFXForward.getSeller()));
                req.using("The lender may not change when settling.", inputFXForward.getBuyer().equals(outputFXForward.getBuyer()));
                req.using("The linearId may not change when settling.", inputFXForward.getLinearId().equals(outputFXForward.getLinearId()));
            }

            // Checks the required parties have signed.
            req.using("Both lender and borrower together only must sign obligation settle transaction.", signers.equals(keysFromParticipants(inputFXForward)));
            return null;
        });
    }
}