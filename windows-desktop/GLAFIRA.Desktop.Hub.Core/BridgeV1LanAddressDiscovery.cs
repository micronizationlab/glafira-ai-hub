using System.Net;
using System.Net.NetworkInformation;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Discovers only active, private LAN addresses suitable for the owner-started HTTPS host.
/// Public, loopback, tunnel and link-local addresses are excluded by the shared policy.
/// </summary>
public static class BridgeV1LanAddressDiscovery
{
    public static IReadOnlyList<IPAddress> GetPrivateAddresses()
    {
        var addresses = new HashSet<IPAddress>();
        NetworkInterface[] networks;
        try
        {
            networks = NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            return [];
        }

        foreach (var network in networks)
        {
            if (network.OperationalStatus != OperationalStatus.Up ||
                network.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                continue;

            IPInterfaceProperties properties;
            try
            {
                properties = network.GetIPProperties();
            }
            catch (NetworkInformationException)
            {
                continue;
            }

            foreach (var unicast in properties.UnicastAddresses)
            {
                var address = unicast.Address;
                if (BridgeV1LanAddressPolicy.IsPrivateLanAddress(address))
                    addresses.Add(address);
            }
        }

        return addresses
            .OrderBy(address => address.AddressFamily.ToString(), StringComparer.Ordinal)
            .ThenBy(address => address.ToString(), StringComparer.Ordinal)
            .ToArray();
    }
}
