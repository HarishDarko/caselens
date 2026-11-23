package com.harishdarko.caselens.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.harishdarko.caselens.demo.DemoPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class DemoRateLimitFilterTest {
    private final RateLimitService limits = new RateLimitService(Clock.systemUTC(), 30, Duration.ofMinutes(1));
    private static final String SECRET = "test-session-secret-that-is-at-least-thirty-two-bytes";
    private final DemoRateLimitFilter.Limits perMinute = new DemoRateLimitFilter.Limits(20, 30, 30, 10, 20);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsTheGatewayClientAddressOnlyWhenTrusted() {
        MockHttpServletRequest request = post("/api/demo/session");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-CaseLens-Client-Ip", "198.51.100.10");

        assertThat(filter(true).clientAddress(request)).isEqualTo("198.51.100.10");
        assertThat(filter(false).clientAddress(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void capsTheLengthOfAClientAddress() {
        MockHttpServletRequest request = post("/api/demo/session");
        request.addHeader("X-CaseLens-Client-Ip", "9".repeat(5_000));

        assertThat(filter(true).clientAddress(request)).hasSize(DemoRateLimitFilter.MAX_CLIENT_ADDRESS_LENGTH);
    }

    /** Follow-up to CL2-04: the bucket table never holds a raw address. */
    @Test
    void keysAnonymousBucketsByTheClientKeyAndNeverTheAddress() {
        MockHttpServletRequest session = post("/api/demo/session");
        session.addHeader("X-CaseLens-Client-Ip", "198.51.100.10");
        MockHttpServletRequest sameSlash64 = post("/api/demo/session");
        sameSlash64.addHeader("X-CaseLens-Client-Ip", "2001:db8:1:2::1");
        MockHttpServletRequest sameSlash64Again = post("/api/demo/session");
        sameSlash64Again.addHeader("X-CaseLens-Client-Ip", "2001:db8:1:2::ffff");
        MockHttpServletRequest unauthenticated = post("/api/tickets");
        unauthenticated.addHeader("X-CaseLens-Client-Ip", "198.51.100.10");
        DemoRateLimitFilter filter = filter(true);

        String key = new ClientAddresses(true, SECRET).keyFor("198.51.100.10");
        assertThat(filter.bucketFor(session)).isEqualTo(new DemoRateLimitFilter.Bucket("session:" + key, 20));
        assertThat(filter.bucketFor(session).key()).doesNotContain("198.51");
        assertThat(filter.bucketFor(unauthenticated)).isEqualTo(new DemoRateLimitFilter.Bucket("ticket:" + key, 10));
        assertThat(filter.bucketFor(sameSlash64)).isEqualTo(filter.bucketFor(sameSlash64Again));
    }

    @Test
    void limitsTicketCreationAndFeedbackPerWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new DemoPrincipal(workspaceId, java.time.Instant.parse("2040-07-11T12:00:00Z")), null, List.of()));
        DemoRateLimitFilter filter = filter(true);

        assertThat(filter.bucketFor(post("/api/tickets")))
                .isEqualTo(new DemoRateLimitFilter.Bucket("ticket:" + workspaceId, 10));
        assertThat(filter.bucketFor(post("/api/tickets/" + UUID.randomUUID() + "/feedback")))
                .isEqualTo(new DemoRateLimitFilter.Bucket("feedback:" + workspaceId, 20));
        assertThat(filter.bucketFor(post("/api/tickets/" + UUID.randomUUID() + "/triage")))
                .isEqualTo(new DemoRateLimitFilter.Bucket("triage:" + workspaceId, 30));
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/tickets");
        assertThat(filter.bucketFor(read)).isNull();
    }

    private DemoRateLimitFilter filter(boolean trustGatewayClientIp) {
        return new DemoRateLimitFilter(limits, new ObjectMapper(), new ClientAddresses(trustGatewayClientIp, SECRET),
                perMinute);
    }

    private static MockHttpServletRequest post(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
