package com.iwhalecloud.byai.gateway.direct;

import java.util.function.Supplier;

/**
 * Runs Direct bookkeeping in its own transaction. A unique-key race inside the caller's
 * transaction would otherwise abort it on PostgreSQL/openGauss, and the record must commit
 * independently of the chat request that triggered it.
 */
@FunctionalInterface
public interface DirectTransactions {

    <T> T requiresNew(Supplier<T> work);

    /** For tests and callers already outside any transaction. */
    DirectTransactions DIRECT = new DirectTransactions() {
        @Override
        public <T> T requiresNew(Supplier<T> work) {
            return work.get();
        }
    };
}
