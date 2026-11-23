package com.harishdarko.caselens.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressesTest {
    private static final String SECRET = "test-session-secret-that-is-at-least-thirty-two-bytes";
    private final ClientAddresses addresses = new ClientAddresses(true, SECRET);

    @Test
    void theClientKeyIsAnOpaqueHexValueThatNeverHoldsTheAddress() {
        String key = addresses.keyFor("198.51.100.10");

        assertThat(key).matches("[0-9a-f]{32}").doesNotContain("198").isEqualTo(addresses.keyFor("198.51.100.10"));
        assertThat(addresses.keyFor("198.51.100.11")).isNotEqualTo(key);
        assertThat(new ClientAddresses(true, "another-secret-that-is-at-least-thirty-two-bytes").keyFor("198.51.100.10"))
                .isNotEqualTo(key);
    }

    @Test
    void oneIpv6Slash64IsOneClientHoweverTheAddressIsWritten() {
        String key = addresses.keyFor("2001:db8:1:2::1");

        assertThat(addresses.keyFor("2001:0DB8:0001:0002:ffff:ffff:ffff:fffe")).isEqualTo(key);
        assertThat(addresses.keyFor("[2001:db8:1:2:0:0:0:9]")).isEqualTo(key);
        assertThat(addresses.keyFor("2001:db8:1:3::1")).isNotEqualTo(key);
        assertThat(ClientAddresses.network("2001:db8::1")).isEqualTo("2001:db8:0:0::/64");
        assertThat(ClientAddresses.network("::1")).isEqualTo("0:0:0:0::/64");
    }

    @Test
    void ipv4AndUnusualValuesAreKeyedAsTheyAre() {
        assertThat(ClientAddresses.network("198.51.100.10")).isEqualTo("198.51.100.10");
        assertThat(ClientAddresses.network("::ffff:198.51.100.10")).isEqualTo("198.51.100.10");
        assertThat(ClientAddresses.network("unknown")).isEqualTo("unknown");
        assertThat(ClientAddresses.network("1:2:3")).isEqualTo("1:2:3");
        assertThat(ClientAddresses.network("1::2::3")).isEqualTo("1::2::3");
        assertThat(ClientAddresses.network("12345:1:2:3::")).isEqualTo("12345:1:2:3::");
    }

    @Test
    void readsTheGatewayClientAddressOnlyWhenTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader(ClientAddresses.GATEWAY_HEADER, "198.51.100.10");

        assertThat(addresses.address(request)).isEqualTo("198.51.100.10");
        assertThat(new ClientAddresses(false, SECRET).address(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void capsTheLengthOfAClientAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAddresses.GATEWAY_HEADER, "9".repeat(5_000));

        assertThat(addresses.address(request)).hasSize(ClientAddresses.MAX_CLIENT_ADDRESS_LENGTH);
    }

    @Test
    void theRequestKeyFollowsTheSameTrustRuleAsTheRateLimits() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader(ClientAddresses.GATEWAY_HEADER, "198.51.100.10");

        assertThat(addresses.clientKey(request)).isEqualTo(addresses.keyFor("198.51.100.10"));
        assertThat(new ClientAddresses(false, SECRET).clientKey(request)).isEqualTo(addresses.keyFor("10.0.0.5"));
    }

    @Test
    void keysNeedASecret() {
        assertThatThrownBy(() -> new ClientAddresses(true).keyFor("198.51.100.10"))
                .isInstanceOf(IllegalStateException.class);
    }
}
