package com.finfabrik.corda;

import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.LinearState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.crypto.NullKeys;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.serialization.ConstructorForDeserialization;
import net.corda.finance.contracts.Commodity;
import net.corda.finance.contracts.Tenor;

import java.security.PublicKey;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static net.corda.core.utilities.EncodingUtils.toBase58String;

public class FXForward implements LinearState {
    private final Amount<Currency> base;
    private final Amount<Commodity> terms;
    private final AbstractParty buyer;
    private final AbstractParty seller;
    private final Tenor tenor;
    private final UniqueIdentifier linearId;

    @ConstructorForDeserialization
    public FXForward(Amount<Currency> base, Amount<Commodity> terms, AbstractParty buyer, AbstractParty seller, Tenor tenor, UniqueIdentifier linearId) {
        this.base = base;
        this.terms = terms;
        this.buyer = buyer;
        this.seller = seller;
        this.tenor = tenor;
        this.linearId = linearId;
    }

    public FXForward(Amount<Currency> base, Amount<Commodity> terms, AbstractParty buyer, AbstractParty seller, Tenor tenor) {
        this(base, terms, buyer, seller, tenor, new UniqueIdentifier());
    }

    public Amount<Currency> getBase() {
        return base;
    }

    public Amount<Commodity> getTerms() {
        return terms;
    }

    public AbstractParty getBuyer() {
        return buyer;
    }

    public AbstractParty getSeller() {
        return seller;
    }

    public Tenor getTenor() { return tenor; }

    @Override
    public UniqueIdentifier getLinearId() {
        return linearId;
    }

    @Override
    public List<AbstractParty> getParticipants() {
        return ImmutableList.of(buyer, seller);
    }

    public List<PublicKey> getParticipantKeys() {
        return getParticipants().stream().map(AbstractParty::getOwningKey).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        String lenderString;
        if (this.buyer instanceof Party) {
            lenderString = ((Party) buyer).getName().getOrganisation();
        } else {
            PublicKey lenderKey = this.buyer.getOwningKey();
            lenderString = toBase58String(lenderKey);
        }

        String borrowerString;
        if (this.seller instanceof Party) {
            borrowerString = ((Party) seller).getName().getOrganisation();
        } else {
            PublicKey borrowerKey = this.seller.getOwningKey();
            borrowerString = toBase58String(borrowerKey);
        }

        return String.format("FXForward(%s): %s owes %s %s %s %s.",
            this.linearId, borrowerString, lenderString, this.base, this.terms, this.tenor);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FXForward)) {
            return false;
        }
        FXForward other = (FXForward) obj;
        return base.equals(other.getBase())
            && terms.equals(other.getTerms())
            && buyer.equals(other.getBuyer())
            && seller.equals(other.getSeller())
            && tenor.equals(other.getTenor())
            && linearId.equals(other.getLinearId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, terms, buyer, seller, tenor, linearId);
    }
}