package io.github.forest611.mcclientmcp.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Decides which servers the client may connect to. Loopback, private (RFC 1918), link-local and IPv6 unique-local
 * addresses are always allowed; anything else must be listed explicitly. The check is on the resolved IP address.
 */
public final class HostAllowlist {
    private final List<String> extra;
    private volatile String lastBlocked;

    public HostAllowlist(List<String> extra) {
        this.extra = List.copyOf(extra);
    }

    public boolean isAllowed(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && (bytes[0] & 0xfe) == 0xfc) {
            return true;
        }
        for (String entry : extra) {
            if (matches(entry, address, bytes)) {
                return true;
            }
        }
        return false;
    }

    /** Remembers a refused connection so the connect tool can report it as such. */
    public void noteBlocked(String host) {
        lastBlocked = host;
    }

    /** @return the host of the most recent refused connection, clearing it */
    public String takeBlocked() {
        String host = lastBlocked;
        lastBlocked = null;
        return host;
    }

    private static boolean matches(String entry, InetAddress address, byte[] bytes) {
        try {
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                byte[] network = InetAddress.getByName(entry.substring(0, slash)).getAddress();
                int prefix = Integer.parseInt(entry.substring(slash + 1));
                return network.length == bytes.length && prefixMatches(network, bytes, prefix);
            }
            for (InetAddress candidate : InetAddress.getAllByName(entry)) {
                if (candidate.equals(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    private static boolean prefixMatches(byte[] network, byte[] address, int prefix) {
        if (prefix < 0 || prefix > network.length * 8) {
            return false;
        }
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 0x80 >> (bit % 8);
            if ((network[bit / 8] & mask) != (address[bit / 8] & mask)) {
                return false;
            }
        }
        return true;
    }
}
