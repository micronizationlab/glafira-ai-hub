using System.Net;
using System.Runtime.Versioning;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using GLAFIRA.Desktop.Hub.Core;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

[SupportedOSPlatform("windows")]
public sealed class BridgeV1TlsCertificateStoreTests : IDisposable
{
    private readonly string _identityPath = Path.Combine(
        Path.GetTempPath(), "glafira-bridge-tls-" + Guid.NewGuid().ToString("N") + ".dpapi");

    [Fact]
    public void LoadOrCreatePersistsProtectedIdentityAndReusesTheSameCertificate()
    {
        var store = new BridgeV1TlsCertificateStore(_identityPath);
        var addresses = new[] { IPAddress.Parse("192.168.50.20"), IPAddress.Parse("10.12.0.8") };

        var first = store.LoadOrCreate("hub-test-0001", addresses);
        var second = store.LoadOrCreate("hub-test-0001", addresses);
        try
        {
            Assert.True(first.Certificate.HasPrivateKey);
            Assert.Equal(first.FingerprintSha256, second.FingerprintSha256);
            Assert.Equal(addresses.OrderBy(x => x.ToString()).Select(x => x.ToString()),
                second.BindAddresses.OrderBy(x => x.ToString()).Select(x => x.ToString()));
            Assert.True(first.NotAfter > DateTimeOffset.UtcNow.AddDays(150));

            var protectedBytes = File.ReadAllBytes(_identityPath);
            Assert.DoesNotContain(Encoding.UTF8.GetBytes("hub-test-0001"), protectedBytes);
            Assert.DoesNotContain(Encoding.UTF8.GetBytes("PfxBase64"), protectedBytes);
        }
        finally
        {
            first.Certificate.Dispose();
            second.Certificate.Dispose();
        }
    }

    [Fact]
    public void AddressChangeRequiresExplicitRotationAndReturnsPreviousPin()
    {
        var store = new BridgeV1TlsCertificateStore(_identityPath);
        var original = new[] { IPAddress.Parse("192.168.50.20") };
        var changed = new[] { IPAddress.Parse("192.168.50.21") };
        var first = store.LoadOrCreate("hub-test-0001", original);
        try
        {
            Assert.Throws<InvalidOperationException>(() =>
                store.LoadOrCreate("hub-test-0001", changed));

            var rotated = store.Rotate("hub-test-0001", changed);
            try
            {
                Assert.Equal(first.FingerprintSha256, rotated.PreviousFingerprintSha256);
                Assert.NotEqual(first.FingerprintSha256, rotated.FingerprintSha256);
                Assert.Equal("192.168.50.21", Assert.Single(rotated.BindAddresses).ToString());
            }
            finally
            {
                rotated.Certificate.Dispose();
            }
        }
        finally
        {
            first.Certificate.Dispose();
        }
    }

    public void Dispose()
    {
        foreach (var file in new[] { _identityPath, _identityPath + ".tmp" })
        {
            if (File.Exists(file)) File.Delete(file);
        }
    }
}

public sealed class BridgeV1HttpsHostOptionsTests
{
    [Fact]
    public void AcceptsOnlyExplicitPrivateLanBindings()
    {
        var options = new BridgeV1HttpsHostOptions(
            "hub-test-0001",
            [IPAddress.Parse("192.168.10.15"), IPAddress.Parse("10.1.2.3")],
            8443);

        options.Validate();
    }

    [Theory]
    [InlineData("8.8.8.8")]
    [InlineData("127.0.0.1")]
    [InlineData("0.0.0.0")]
    [InlineData("169.254.10.2")]
    public void RejectsPublicLoopbackWildcardAndLinkLocalBindings(string address)
    {
        var options = new BridgeV1HttpsHostOptions(
            "hub-test-0001", [IPAddress.Parse(address)], 8443);

        Assert.Throws<ArgumentException>(() => options.Validate());
    }

    [Fact]
    public void RejectsPrivilegedOrInvalidPorts()
    {
        var options = new BridgeV1HttpsHostOptions(
            "hub-test-0001", [IPAddress.Parse("192.168.10.15")], 443);

        Assert.Throws<ArgumentOutOfRangeException>(() => options.Validate());
    }

    [Fact]
    public void RejectsDuplicateLanAddresses()
    {
        var address = IPAddress.Parse("192.168.10.15");
        var options = new BridgeV1HttpsHostOptions(
            "hub-test-0001", [address, address], 8443);

        Assert.Throws<ArgumentException>(() => options.Validate());
    }
}
