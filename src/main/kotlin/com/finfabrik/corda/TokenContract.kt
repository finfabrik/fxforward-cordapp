package com.finfabrik.corda

import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCash

/**
 * The TokenContract can handle three transaction types involving [TokenState]s.
 * - Issuance: Issuing a new [TokenState] on the ledger, which is a bilateral agreement between two parties.
 * - Transfer: Re-assigning the lender/beneficiary.
 * - Settle: Fully or partially settling the [TokenState] using the Corda [Cash] contract.
 *
 * LegalProseReference: this is just a dummy string for the time being.
 */
@LegalProseReference(uri = "<prose_contract_uri>")
class TokenContract : Contract {
    companion object {
        @JvmStatic
        val Token_CONTRACT_ID = "com.finfabrik.corda.TokenContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [TokenContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<TokenContract.Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat {
                "No inputs should be consumed when issuing an Token." using (tx.inputs.isEmpty())
                "Only one output state should be created when issuing an Token." using (tx.outputs.size == 1)
                val Token = tx.outputStates.single() as TokenState
                "A newly issued Token must have a positive amount." using (Token.amount.quantity > 0)
                "Both lender and borrower together only may sign Token issue transaction." using
                        (command.signers.toSet() == Token.participants.map { it.owningKey }.toSet())
            }
            is Commands.Transfer -> requireThat {
                "An Token transfer transaction should only consume one input state." using (tx.inputs.size == 1)
                "An Token transfer transaction should only create one output state." using (tx.outputs.size == 1)
                val input = tx.inputStates.single() as TokenState
                val output = tx.outputStates.single() as TokenState
                "Only the owner property may change." using (input == output.withNewOwner(input.owner))
                "The owner property must change in a transfer." using (input.owner != output.owner)
                "Both existing owner and new ower together only must sign Token settle transaction." using
                        (command.signers.toSet() == (input.participants.map { it.owningKey }.toSet() `union`
                                output.participants.map { it.owningKey }.toSet()))
            }
        }
    }
}
