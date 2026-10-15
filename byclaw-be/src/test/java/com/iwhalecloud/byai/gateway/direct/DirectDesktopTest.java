package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DirectDesktopTest {

    private static final RuntimeOwnerScope OWNER = new RuntimeOwnerScope(100L, "u1", 1L);
    private final AtomicLong now = new AtomicLong(1_790_000_000_000L);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };

    private DirectDesktop desktop(RuntimeScopeAuthority authority) {
        return new DirectDesktop(mock(RuntimeBindingService.class), mock(CubeSandboxClient.class), authority, 8081,
            "master-secret-for-tests-0123456789abcdef", clock);
    }

    @Test
    void aViewerTicketNamesItsOwnerUntilItExpiresOrIsTampered() {
        DirectDesktop desktop = desktop(RuntimeScopeAuthority.ALWAYS);
        String ticket = desktop.ticket(OWNER, "login-1");
        assertThat(desktop.verify(ticket)).contains(OWNER);
        String forged = ticket.substring(0, ticket.lastIndexOf('.') + 1) + "0".repeat(64);
        assertThat(desktop.verify(forged)).isEmpty();
        assertThat(desktop.verify("not-a-ticket")).isEmpty();
        assertThat(desktop(RuntimeScopeAuthority.ALWAYS).verify(ticket)).as("same secret, same verdict").contains(OWNER);
        assertThat(new DirectDesktop(mock(RuntimeBindingService.class), mock(CubeSandboxClient.class),
            RuntimeScopeAuthority.ALWAYS, 8081, "another-master-secret-0123456789abcdef", clock).verify(ticket)).isEmpty();
        now.addAndGet(DirectDesktop.TICKET_TTL.toMillis() + 1);
        assertThat(desktop.verify(ticket)).as("expired").isEmpty();
    }

    @Test
    void aRevokedScopeLosesItsDesktop() {
        assertThat(desktop(owner -> "membership_inactive").verify(desktop(RuntimeScopeAuthority.ALWAYS)
            .ticket(OWNER, "login-1"))).isEmpty();
    }

    @Test
    void viewersAreCountedPerOwner() {
        DirectDesktop desktop = desktop(RuntimeScopeAuthority.ALWAYS);
        desktop.opened("u100.e1");
        desktop.opened("u100.e1");
        desktop.closed("u100.e1");
        assertThat(desktop.inUse("u100.e1")).isTrue();
        desktop.closed("u100.e1");
        assertThat(desktop.inUse("u100.e1")).isFalse();
        assertThat(desktop.inUse("u200.e1")).isFalse();
    }
}
