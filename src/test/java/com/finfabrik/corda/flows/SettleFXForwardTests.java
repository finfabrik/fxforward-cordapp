package com.finfabrik.corda.flows;

import com.finfabrik.corda.FXForward;
import com.finfabrik.corda.Token;
import net.corda.core.transactions.SignedTransaction;
import net.corda.finance.contracts.Tenor;
import net.corda.finance.contracts.asset.Cash;
import org.junit.Test;

import java.util.List;

import static net.corda.finance.Currencies.POUNDS;
import static net.corda.testing.internal.InternalTestUtilsKt.chooseIdentity;
import static org.junit.Assert.assertEquals;

public class SettleFXForwardTests extends FXForwardTests {

  @Test
  public void fullySettleNonAnonymousForward() throws Exception {
    // Self issue cash.
    selfIssueCash(a, POUNDS(1000));
    network.waitQuiescent();

    SignedTransaction stx = issueToken(b, 100, "BTC");
    network.waitQuiescent();

    Token issuedToken = (Token) stx.getTx().getOutputStates().get(0);

    // Issue obligation.
    stx = issueFXForward(a, b, POUNDS(1000), tokenFaucet("FAB", 100), new Tenor("1M"), false);
    network.waitQuiescent();
    FXForward issuedForward = (FXForward) stx.getTx().getOutputStates().get(0);

    // Attempt settlement.
    SignedTransaction settleTransaction = settleFXForward(issuedForward.getLinearId(), issuedToken.getLinearId(), a, true);
    network.waitQuiescent();
    assert(settleTransaction.getTx().outputsOfType(FXForward.class).isEmpty());

    // Check both parties have the transaction.
    SignedTransaction aTx = a.getServices().getValidatedTransactions().getTransaction(settleTransaction.getId());
    SignedTransaction bTx = b.getServices().getValidatedTransactions().getTransaction(settleTransaction.getId());
    assertEquals(aTx, bTx);

    Token outputToken = settleTransaction.getTx().outputsOfType(Token.class).get(0);
    assertEquals(outputToken.getOwner(), chooseIdentity(a.getInfo()));

    List<Cash.State> outputCash = settleTransaction.getTx().outputsOfType(Cash.State.class);
    assertEquals(outputCash.get(0).getOwner(), chooseIdentity(b.getInfo()));
  }
}
