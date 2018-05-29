package com.finfabrik.corda

import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.finance.contracts.Commodity


data class TokenState(val amount: Amount<Commodity>,
                      val owner: Party,
                      override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState {

    override val participants: List<Party> get() = listOf(owner)

    fun withNewOwner(newOwner: Party) = copy(owner = newOwner)
}