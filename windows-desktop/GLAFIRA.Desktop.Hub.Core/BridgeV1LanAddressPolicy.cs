using System.Net;
using System.Net.Sockets;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>Allows only RFC1918 IPv4 and IPv6 unique-local addresses for Bridge host binding.</summary>
public static class BridgeV1LanAddressPolicy
{
    public static bool IsPrivateLanAddress(IPAddress address)
    {
        ArgumentNullException.ThrowIfNull(address);
        if (IPAddress.IsLoopback(address) || address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.IPv6Any) || address.IsIPv6Multicast ||
            address.IsIPv4MappedToIPv6)
            return false;

        var bytes = address.GetAddressBytes();
        if (address.AddressFamily == AddressFamily.InterNetwork)
        {
            return bytes[0] == 10 ||
                (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) ||
                (bytes[0] == 192 && bytes[1] == 168);
        }

        return address.AddressFamily == AddressFamily.InterNetworkV6 &&
            (bytes[0] & 0xFE) == 0xFC;
    }
}
