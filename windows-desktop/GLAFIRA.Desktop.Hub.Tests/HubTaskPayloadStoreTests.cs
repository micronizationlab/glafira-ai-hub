using System.Security.Cryptography;
using System.Runtime.Versioning;
using System.Text;
using GLAFIRA.Desktop.Hub.Core;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

[SupportedOSPlatform("windows")]
public sealed class HubTaskPayloadStoreTests : IDisposable
{
    private readonly string _root = Path.Combine(
        Path.GetTempPath(), "glafira-hub-payloads-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task StoresPayloadEncryptedAndResolvesByContentAddress()
    {
        var store = new HubTaskPayloadStore(_root);
        var payload = Encoding.UTF8.GetBytes("""{"instruction":"private task content"}""");

        var reference = await store.StoreAsync(payload);
        var resolved = await store.ResolveAsync(reference.PayloadRef, CancellationToken.None);

        var resolvedBytes = Assert.IsType<byte[]>(resolved);
        Assert.Equal(reference.PayloadRef, reference.PayloadSha256);
        Assert.Equal(payload, resolvedBytes);
        var encryptedText = Encoding.UTF8.GetString(
            File.ReadAllBytes(Path.Combine(_root, reference.PayloadRef + ".dpapi")));
        Assert.DoesNotContain("private task content", encryptedText);
        CryptographicOperations.ZeroMemory(resolvedBytes);
    }

    [Fact]
    public async Task DeduplicatesIdenticalPayloads()
    {
        var store = new HubTaskPayloadStore(_root);
        var payload = Encoding.UTF8.GetBytes("same payload");

        var first = await store.StoreAsync(payload);
        var second = await store.StoreAsync(payload);

        Assert.Equal(first, second);
        Assert.Single(Directory.GetFiles(_root, "*.dpapi"));
    }

    [Fact]
    public async Task RejectsTamperedCiphertextAndMalformedReferences()
    {
        var store = new HubTaskPayloadStore(_root);
        var reference = await store.StoreAsync(Encoding.UTF8.GetBytes("protected"));
        var path = Path.Combine(_root, reference.PayloadRef + ".dpapi");
        var bytes = File.ReadAllBytes(path);
        bytes[bytes.Length / 2] ^= 0x40;
        File.WriteAllBytes(path, bytes);

        Assert.Null(await store.ResolveAsync(reference.PayloadRef, CancellationToken.None));
        Assert.Null(await store.ResolveAsync("../outside", CancellationToken.None));
        Assert.Null(await store.ResolveAsync(new string('g', 64), CancellationToken.None));
    }

    [Fact]
    public async Task RejectsEmptyAndOversizedPayloads()
    {
        var store = new HubTaskPayloadStore(_root);

        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(async () =>
        {
            await store.StoreAsync(ReadOnlyMemory<byte>.Empty);
        });
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(async () =>
        {
            await store.StoreAsync(new byte[65_537]);
        });
    }

    public void Dispose()
    {
        if (Directory.Exists(_root))
            Directory.Delete(_root, recursive: true);
    }
}

public sealed class BridgeV1HubIdentityStoreTests
{
    [Fact]
    public void HubIdentityRemainsStableAcrossRestarts()
    {
        var path = Path.Combine(Path.GetTempPath(), "glafira-hub-id-" + Guid.NewGuid().ToString("N"), "hub-id.txt");
        try
        {
            var first = new BridgeV1HubIdentityStore(path).LoadOrCreate();
            var second = new BridgeV1HubIdentityStore(path).LoadOrCreate();

            Assert.Equal(first, second);
            Assert.True(Guid.TryParseExact(first, "N", out _));
        }
        finally
        {
            var directory = Path.GetDirectoryName(path)!;
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void CorruptHubIdentityFailsClosedInsteadOfSilentlyRegenerating()
    {
        var directory = Path.Combine(Path.GetTempPath(), "glafira-hub-id-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "hub-id.txt");
        try
        {
            File.WriteAllText(path, "not-a-hub-id");
            Assert.Throws<InvalidDataException>(() => new BridgeV1HubIdentityStore(path).LoadOrCreate());
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}

public sealed class BridgeV1LanAddressDiscoveryTests
{
    [Fact]
    public void ReturnsOnlyUniquePrivateLanAddresses()
    {
        var addresses = BridgeV1LanAddressDiscovery.GetPrivateAddresses();

        Assert.Equal(addresses.Count, addresses.Distinct().Count());
        Assert.All(addresses, address => Assert.True(BridgeV1LanAddressPolicy.IsPrivateLanAddress(address)));
    }
}
