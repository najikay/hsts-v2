package server.console;

import java.util.Objects;

/**
 * One IPv4 address a client could be pointed at (Logic tier, E19.1 / F13.2).
 *
 * <p>The interface name travels with the address because it is what makes the
 * console's dropdown usable on a Windows demo machine. "192.168.56.1" and
 * "192.168.1.42" look equally plausible to a person reading a list; "VirtualBox
 * Host-Only Network" and "Wi-Fi" do not.
 *
 * @param ip            the dotted-quad address
 * @param interfaceName the display name of the interface it belongs to
 * @param siteLocal     {@code true} for RFC 1918 ranges (10/8, 172.16/12,
 *                      192.168/16), which is what a LAN demo actually uses
 */
public record NetworkAddress(String ip, String interfaceName, boolean siteLocal) {

    public NetworkAddress {
        Objects.requireNonNull(ip, "ip");
        ip = ip.trim();
        if (ip.isEmpty()) {
            throw new IllegalArgumentException("An address needs an ip");
        }
        interfaceName = interfaceName == null || interfaceName.isBlank()
                ? "unknown interface" : interfaceName.trim();
    }

    /** @return {@code "192.168.1.42 (Wi-Fi)"}, the form the console's picker shows. */
    public String display() {
        return ip + " (" + interfaceName + ')';
    }

    @Override
    public String toString() {
        return display();
    }
}
