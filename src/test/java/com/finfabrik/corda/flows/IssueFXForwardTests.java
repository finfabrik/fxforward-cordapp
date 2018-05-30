package com.finfabrik.corda.flows;

import com.finfabrik.corda.FXForward;
import net.corda.core.transactions.SignedTransaction;
import net.corda.finance.contracts.Tenor;
import org.junit.Test;

import static net.corda.finance.Currencies.POUNDS;
import static org.junit.Assert.assertEquals;

public class IssueFXForwardTests extends FXForwardTests {

  @Test
  public void issueNonAnonymousForwardSuccessfully() throws Exception {
    SignedTransaction stx = issueFXForward(a, b, POUNDS(1000), tokenFaucet("FAB", 100), new Tenor("1M"), false);

    network.waitQuiescent();

    FXForward aForward = (FXForward) a.getServices().loadState(stx.getTx().outRef(0).getRef()).getData();
    FXForward bForward = (FXForward) b.getServices().loadState(stx.getTx().outRef(0).getRef()).getData();

    assertEquals(aForward, bForward);
  }
}
