package com.finfabrik.corda

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction


@LegalProseReference(uri = "<prose_contract_uri>")
class TokenContract : Contract {
    companion object {
        @JvmStatic
        val Token_CONTRACT_ID = "com.finfabrik.corda.TokenContract"
    }


    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<TokenContract.Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat {
                "No inputs should be consumed when issuing an Token." using (tx.inputs.isEmpty())
                "Only one output state should be created when issuing an Token." using (tx.outputs.size == 1)
                val Token = tx.outputStates.single() as Token
                "A newly issued Token must have a positive amount." using (Token.amount.quantity > 0)
                "Both lender and borrower together only may sign Token issue transaction." using
                        (command.signers.toSet() == Token.participants.map { it.owningKey }.toSet())
            }
            is Commands.Transfer -> requireThat {

                val tokenInputs = tx.inputsOfType<Token>()
                val tokenOutputs = tx.outputsOfType<Token>()

                "An Token transfer transaction should only consume one input state." using (tokenInputs.size == 1)
                "An Token transfer transaction should only create one output state." using (tokenOutputs.size == 1)
                val input = tokenInputs.single()
                val output = tokenOutputs.single()
                "Only the owner property may change." using (input == output.withNewOwner(input.owner))
                "The owner property must change in a transfer." using (input.owner != output.owner)
            }
        }
    }
}
