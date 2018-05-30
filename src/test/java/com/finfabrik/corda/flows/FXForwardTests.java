package com.finfabrik.corda.flows;

import com.google.common.collect.ImmutableList;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.OpaqueBytes;
import net.corda.finance.contracts.Commodity;
import net.corda.finance.contracts.Tenor;
import net.corda.finance.flows.CashIssueFlow;
import net.corda.testing.node.MockNetwork;
import net.corda.testing.node.MockNetworkParameters;
import net.corda.testing.node.StartedMockNode;
import org.junit.After;
import org.junit.Before;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.concurrent.ExecutionException;

import static net.corda.testing.internal.InternalTestUtilsKt.chooseIdentity;

abstract class FXForwardTests {

  protected MockNetwork network;
  protected StartedMockNode a;
  protected StartedMockNode b;
  protected StartedMockNode c;

  @Before
  public void setup() {
    network = new MockNetwork(
        ImmutableList.of("com.finfabrik.corda", "net.corda.finance"),
        new MockNetworkParameters().withThreadPerNode(true));

    a = network.createPartyNode(null);
    b = network.createPartyNode(null);
    c = network.createPartyNode(null);

    for (StartedMockNode node : ImmutableList.of(a, b, c)) {
      node.registerInitiatedFlow(IssueFXForward.Responder.class);
      node.registerInitiatedFlow(SettleFXForward.Responder.class);
    }
  }

  @After
  public void tearDown() {
    network.stopNodes();
  }

  public static Amount<Commodity> tokenFaucet(String code, int amount) {
    Commodity token = new Commodity(code, code, 0);
    return Amount.fromDecimal(BigDecimal.valueOf(amount), token);
  }

  protected SignedTransaction issueFXForward( StartedMockNode seller,
                                              StartedMockNode buyer,
                                              Amount<Currency> ccy,
                                              Amount<Commodity> token,
                                              Tenor tenor,
                                              Boolean anonymous) throws InterruptedException, ExecutionException {
    Party buyerParty = chooseIdentity(buyer.getInfo());
    IssueFXForward.Initiator flow = new IssueFXForward.Initiator(ccy, token, buyerParty, tenor, anonymous);
    return seller.startFlow(flow).get();
  }

  protected SignedTransaction settleFXForward(UniqueIdentifier linearId,
                                               StartedMockNode seller,
                                               Boolean anonymous) throws InterruptedException, ExecutionException {

    SettleFXForward.Initiator flow = new SettleFXForward.Initiator(linearId, anonymous);
    return seller.startFlow(flow).get();
  }

  protected SignedTransaction selfIssueCash(StartedMockNode party,
                                            Amount<Currency> amount) throws InterruptedException, ExecutionException {
    Party notary = party.getServices().getNetworkMapCache().getNotaryIdentities().get(0);
    OpaqueBytes issueRef = OpaqueBytes.of("0".getBytes());
    CashIssueFlow.IssueRequest issueRequest = new CashIssueFlow.IssueRequest(amount, issueRef, notary);
    CashIssueFlow flow = new CashIssueFlow(issueRequest);
    return party.startFlow(flow).get().getStx();
  }
}
