package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.iwhalecloud.byai.gateway.direct.DirectAdmissions.Kind;

class DirectAdmissionsTest {

    private static final RuntimeOwnerScope OWNER = new RuntimeOwnerScope(100L, "u1", 0L);
    private final InMemoryRequestAdmissions rows = new InMemoryRequestAdmissions();
    private final DirectAdmissions admissions = new DirectAdmissions(rows);

    @Test
    void theSameRequestIsReservedOnceAndChangedContentConflicts() {
        assertThat(admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64)).kind()).isEqualTo(Kind.NEW);
        assertThat(admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64)).kind()).isEqualTo(Kind.DUPLICATE);
        assertThat(admissions.reserve(OWNER, 9000L, "req-1", "b".repeat(64)).kind()).isEqualTo(Kind.CONFLICT);
        assertThat(admissions.reserve(OWNER, 9001L, "req-1", "b".repeat(64)).kind())
            .as("the key is scoped by session").isEqualTo(Kind.NEW);
        assertThat(admissions.reserve(new RuntimeOwnerScope(200L, "u2", 0L), 9000L, "req-1", "a".repeat(64)).kind())
            .as("another owner never inherits the admission").isEqualTo(Kind.CONFLICT);
    }

    @Test
    void everyStatementRunsInItsOwnTransaction() {
        java.util.concurrent.atomic.AtomicInteger transactions = new java.util.concurrent.atomic.AtomicInteger();
        DirectAdmissions isolated = new DirectAdmissions(rows, new DirectTransactions() {
            @Override
            public <T> T requiresNew(java.util.function.Supplier<T> work) {
                transactions.incrementAndGet();
                return work.get();
            }
        });
        isolated.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        int afterFirst = transactions.get();
        assertThat(isolated.reserve(OWNER, 9000L, "req-1", "a".repeat(64)).kind())
            .as("a duplicate is found by lookup, never by a failing insert in the caller's transaction")
            .isEqualTo(Kind.DUPLICATE);
        assertThat(transactions.get() - afterFirst).isEqualTo(1);
        assertThat(afterFirst).isEqualTo(2);
    }

    @Test
    void bindingRequiresAFreshReservation() {
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.bind(9000L, "req-1", 1L, 2L, "1_2");
        assertThat(rows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.DISPATCHING);
        assertThatThrownBy(() -> admissions.bind(9000L, "req-1", 3L, 4L, "3_4")).isInstanceOf(IllegalStateException.class);
        assertThat(admissions.find(9000L, "req-1").getUserMessageId()).isEqualTo(1L);
    }

    @Test
    void duplicatesAreDescribedByTheirRecordedState() {
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.record(9000L, "req-1", DirectAdmissions.COMPLETED, "done");
        assertThat(DirectAdmissions.describe(admissions.find(9000L, "req-1"))).contains("已执行完成");
        admissions.record(9000L, "req-1", DirectAdmissions.UNKNOWN, "lost");
        assertThat(DirectAdmissions.describe(admissions.find(9000L, "req-1"))).contains("阻止重复执行");
        admissions.record(9000L, "req-1", DirectAdmissions.DISPATCHING, "platform crashed before the runtime answered");
        assertThat(DirectAdmissions.describe(admissions.find(9000L, "req-1"))).contains("尚未确认");
        admissions.record(9000L, "req-1", DirectAdmissions.ACCEPTED, "runtime accepted");
        assertThat(DirectAdmissions.describe(admissions.find(9000L, "req-1"))).contains("已接纳");
    }
}
