package com.finfabrik.corda.flows

import co.paralleluniverse.fibers.Suspendable
import com.finfabrik.corda.TokenContract
import com.finfabrik.corda.Token
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder


@InitiatingFlow
@StartableByRPC
class TokenTransferFlow(val linearId: UniqueIdentifier,
                        val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val TokenStateAndRef =  serviceHub.vaultService.queryBy<Token>(queryCriteria).states.single()
        val inputToken = TokenStateAndRef.state.data

        if (ourIdentity != inputToken.owner) {
            throw IllegalArgumentException("Token transfer can only be initiated by the Token lender.")
        }

        val outputToken = inputToken.withNewOwner(newLender)

        val signers = (inputToken.participants + newLender).map { it.owningKey }
        val transferCommand = Command(TokenContract.Commands.Transfer(), signers)

        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val builder = TransactionBuilder(notary = notary)

        builder.withItems(TokenStateAndRef,
                        StateAndContract(outputToken, TokenContract.Token_CONTRACT_ID),
                        transferCommand)

        builder.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(builder)

        val sessions = (inputToken.participants - ourIdentity + newLender).map { initiateFlow(it) }.toSet()
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        return subFlow(FinalityFlow(stx))
    }
}

@InitiatedBy(TokenTransferFlow::class)
class TokenTransferFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Token transaction" using (output is Token)
            }
        }

        subFlow(signedTransactionFlow)
    }
}