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
        // Stage 1. Retrieve Token specified by linearId from the vault.
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val TokenStateAndRef =  serviceHub.vaultService.queryBy<Token>(queryCriteria).states.single()
        val inputToken = TokenStateAndRef.state.data

        // Stage 2. This flow can only be initiated by the current recipient.
        if (ourIdentity != inputToken.owner) {
            throw IllegalArgumentException("Token transfer can only be initiated by the Token lender.")
        }

        // Stage 3. Create the new Token state reflecting a new lender.
        val outputToken = inputToken.withNewOwner(newLender)

        // Stage 4. Create the transfer command.
        val signers = (inputToken.participants + newLender).map { it.owningKey }
        val transferCommand = Command(TokenContract.Commands.Transfer(), signers)

        // Stage 5. Get a reference to a transaction builder.
        // Note: ongoing work to support multiple notary identities is still in progress.
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val builder = TransactionBuilder(notary = notary)

        // Stage 6. Create the transaction which comprises one input, one output and one command.
        builder.withItems(TokenStateAndRef,
                        StateAndContract(outputToken, TokenContract.Token_CONTRACT_ID),
                        transferCommand)

        // Stage 7. Verify and sign the transaction.
        builder.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(builder)

        // Stage 8. Collect signature from borrower and the new lender and add it to the transaction.
        // This also verifies the transaction and checks the signatures.
        val sessions = (inputToken.participants - ourIdentity + newLender).map { initiateFlow(it) }.toSet()
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        // Stage 9. Notarise and record the transaction in our vaults.
        return subFlow(FinalityFlow(stx))
    }
}

/**
 * This is the flow which signs Token transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
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