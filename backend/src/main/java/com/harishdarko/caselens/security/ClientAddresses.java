package com.harishdarko.caselens.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Who is calling. On Lambda the address is X-CaseLens-Client-Ip, which only ApiHandler sets, from the API Gateway
 * sourceIp, after dropping any copy the client sent. Outside Lambda nothing strips that header, so the socket address
 * is used and a caller cannot pick a new address for every request.
 */
public final class ClientAddresses {
    static final String GATEWAY_HEADER = "X-CaseLens-Client-Ip";
    /** An IPv6 address is at most 45 characters; anything longer is not an address and is cut. */
    static final int MAX_CLIENT_ADDRESS_LENGTH = 64;
    /** 128 bits of the HMAC, as hex: plenty to keep clients apart, and short enough for a rate-limit key. */
    static final int CLIENT_KEY_HEX_LENGTH = 32;
    private static final String HMAC = "HmacSHA256";
    private final boolean trustGatewayClientIp;
    private final SecretKeySpec keySecret;

    /** Addresses only; {@link #clientKey} needs a secret. */
    public ClientAddresses(boolean trustGatewayClientIp) {
        this(trustGatewayClientIp, null);
    }

    public ClientAddresses(boolean trustGatewayClientIp, String keySecret) {
        this.trustGatewayClientIp = trustGatewayClientIp;
        this.keySecret = keySecret == null || keySecret.isBlank() ? null
                : new SecretKeySpec(("caselens-client-key:" + keySecret).getBytes(StandardCharsets.UTF_8), HMAC);
    }

    public String address(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (trustGatewayClientIp) {
            String gatewayAddress = request.getHeader(GATEWAY_HEADER);
            if (gatewayAddress != null && !gatewayAddress.isBlank()) address = gatewayAddress.trim();
        }
        if (address == null) return "unknown";
        return address.length() > MAX_CLIENT_ADDRESS_LENGTH ? address.substring(0, MAX_CLIENT_ADDRESS_LENGTH) : address;
    }

    /**
     * A stable, opaque key for the caller's network, used to count its share of the AI budget. It is an HMAC of the
     * address, so the workspace row and the bucket table never hold the address itself. An IPv6 caller is keyed by
     * its /64, because one home or phone line usually gets a whole /64 and could otherwise rotate addresses freely.
     */
    public String clientKey(HttpServletRequest request) {
        return keyFor(address(request));
    }

    String keyFor(String address) {
        if (keySecret == null) throw new IllegalStateException("Client keys need a secret");
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(keySecret);
            byte[] digest = mac.doFinal(network(address).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, CLIENT_KEY_HEX_LENGTH);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is not available", unavailable);
        }
    }

    /**
     * The IPv6 /64 of an address, written the same way however the caller spelled it; an IPv4-mapped IPv6 address
     * becomes its IPv4 address. Anything else, including IPv4, is returned as it is.
     */
    static String network(String address) {
        if (address.indexOf(':') < 0) return address;
        String text = address.toLowerCase(Locale.ROOT);
        int zone = text.indexOf('%');
        if (zone >= 0) text = text.substring(0, zone);
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
        if (text.startsWith("::ffff:") && text.indexOf('.') > 0) return text.substring("::ffff:".length());
        if (!text.matches("[0-9a-f:]+")) return address;
        String[] groups = expand(text);
        if (groups == null) return address;
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            if (groups[index].isEmpty() || groups[index].length() > 4) return address;
            prefix.append(Integer.toHexString(Integer.parseInt(groups[index], 16))).append(':');
        }
        return prefix.append(":/64").toString();
    }

    /** The eight groups of an IPv6 address without an embedded IPv4 part, or null when it is not one. */
    private static String[] expand(String text) {
        int gap = text.indexOf("::");
        if (gap < 0) {
            String[] groups = text.split(":", -1);
            return groups.length == 8 ? groups : null;
        }
        if (text.indexOf("::", gap + 1) >= 0) return null;
        String left = text.substring(0, gap);
        String right = text.substring(gap + 2);
        String[] head = left.isEmpty() ? new String[0] : left.split(":", -1);
        String[] tail = right.isEmpty() ? new String[0] : right.split(":", -1);
        int missing = 8 - head.length - tail.length;
        if (missing < 1) return null;
        String[] groups = new String[8];
        int index = 0;
        for (String group : head) groups[index++] = group;
        for (int zero = 0; zero < missing; zero++) groups[index++] = "0";
        for (String group : tail) groups[index++] = group;
        return groups;
    }
}
