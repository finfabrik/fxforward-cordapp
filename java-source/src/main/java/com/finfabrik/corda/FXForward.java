package com.finfabrik.corda;

import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.LinearState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.crypto.NullKeys;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.serialization.ConstructorForDeserialization;

import java.security.PublicKey;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static net.corda.core.utilities.EncodingUtils.toBase58String;

public class FXForward implements LinearState {
    private final Amount<Currency> amount;
    private final AbstractParty lender;
    private final AbstractParty borrower;
    private final UniqueIdentifier linearId;

    @ConstructorForDeserialization
    public FXForward(Amount<Currency> amount, AbstractParty lender, AbstractParty borrower, UniqueIdentifier linearId) {
        this.amount = amount;
        this.lender = lender;
        this.borrower = borrower;
        this.linearId = linearId;
    }

    public FXForward(Amount<Currency> amount, AbstractParty lender, AbstractParty borrower) {
        this.amount = amount;
        this.lender = lender;
        this.borrower = borrower;
        this.linearId = new UniqueIdentifier();
    }

    public Amount<Currency> getAmount() {
        return amount;
    }

    public AbstractParty getLender() {
        return lender;
    }

    public AbstractParty getBorrower() {
        return borrower;
    }


    @Override
    public UniqueIdentifier getLinearId() {
        return linearId;
    }

    @Override
    public List<AbstractParty> getParticipants() {
        return ImmutableList.of(lender, borrower);
    }

        public FXForward withNewLender(AbstractParty newLender) {
        return new FXForward(this.amount, newLender, this.borrower, this.linearId);
    }

    public FXForward withoutLender() {
        return new FXForward(this.amount, NullKeys.INSTANCE.getNULL_PARTY(), this.borrower, this.linearId);
    }

    public List<PublicKey> getParticipantKeys() {
        return getParticipants().stream().map(AbstractParty::getOwningKey).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        String lenderString;
        if (this.lender instanceof Party) {
            lenderString = ((Party) lender).getName().getOrganisation();
        } else {
            PublicKey lenderKey = this.lender.getOwningKey();
            lenderString = toBase58String(lenderKey);
        }

        String borrowerString;
        if (this.borrower instanceof Party) {
            borrowerString = ((Party) borrower).getName().getOrganisation();
        } else {
            PublicKey borrowerKey = this.borrower.getOwningKey();
            borrowerString = toBase58String(borrowerKey);
        }

        return String.format("FXForward(%s): %s owes %s %s.",
                this.linearId, borrowerString, lenderString, this.amount);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FXForward)) {
            return false;
        }
        FXForward other = (FXForward) obj;
        return amount.equals(other.getAmount())
                && lender.equals(other.getLender())
                && borrower.equals(other.getBorrower())
                && linearId.equals(other.getLinearId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, lender, borrower, linearId);
    }
}