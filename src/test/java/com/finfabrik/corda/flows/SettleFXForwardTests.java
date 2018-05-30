package com.finfabrik.corda.flows;

import com.finfabrik.corda.FXForward;
import net.corda.core.transactions.SignedTransaction;
import net.corda.finance.contracts.Tenor;
import org.junit.Test;

import static net.corda.finance.Currencies.POUNDS;
import static org.junit.Assert.assertEquals;

public class SettleFXForwardTests extends FXForwardTests {

  @Test
  public void fullySettleNonAnonymousForward() throws Exception {
    // Self issue cash.
    selfIssueCash(a, POUNDS(1000));
    network.waitQuiescent();

    // Issue obligation.
    SignedTransaction stx = issueFXForward(a, b, POUNDS(1000), tokenFaucet("FAB", 100), new Tenor("1M"), false);
    network.waitQuiescent();
    FXForward issuedForward = (FXForward) stx.getTx().getOutputStates().get(0);

    // Attempt settlement.
    SignedTransaction settleTransaction = settleFXForward(issuedForward.getLinearId(), a, true);
    network.waitQuiescent();
    assert(settleTransaction.getTx().outputsOfType(FXForward.class).isEmpty());

    // Check both parties have the transaction.
    SignedTransaction aTx = a.getServices().getValidatedTransactions().getTransaction(settleTransaction.getId());
    SignedTransaction bTx = b.getServices().getValidatedTransactions().getTransaction(settleTransaction.getId());
    assertEquals(aTx, bTx);
  }
}
