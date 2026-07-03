package com.crowdodge.shared.infra.messaging

import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventHandler
import com.crowdodge.shared.kernel.DomainEventPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.statements.SuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

class TransactionalInProcessDomainEventPublisher(
    private val handlerProvider: () -> List<DomainEventHandler>,
    private val scope: CoroutineScope,
) : DomainEventPublisher {
    constructor(
        handlers: List<DomainEventHandler>,
        scope: CoroutineScope,
    ) : this(
        handlerProvider = { handlers },
        scope = scope,
    )

    override suspend fun publish(event: DomainEvent) {
        val transaction = TransactionManager.current()
        transaction.registerInterceptor(
            object : SuspendStatementInterceptor {
                override suspend fun afterCommit(transaction: R2dbcTransaction) {
                    scope.launch {
                        handlerProvider()
                            .filter { handler -> handler.supports(event) }
                            .forEach { handler ->
                                runCatching {
                                    handler.handle(event)
                                }.onFailure { cause ->
                                    logger.error("Domain Event handler failed", cause)
                                }
                            }
                    }
                }
            },
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(TransactionalInProcessDomainEventPublisher::class.java)
    }
}
