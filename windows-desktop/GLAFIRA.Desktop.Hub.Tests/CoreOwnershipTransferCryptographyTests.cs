using System.Security.Cryptography;
using System.Text;
using GLAFIRA.Desktop.Core.Ownership;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class CoreOwnershipTransferCryptographyTests
{
    [Fact]
    public void CanonicalBytesMatchDualCoreOwnershipV1GoldenVector()
    {
        var payload = new OwnershipTransferSigningPayload(
            SchemaVersion: 1,
            TransferId: "transfer-0001",
            Nonce: "nonce-000001",
            TaskId: "task-000001",
            ProjectId: "project-0001",
            OldOwnerCoreId: "android-core-01",
            NewOwnerCoreId: "desktop-core-01",
            OldOwnerGeneration: 7,
            NewOwnerGeneration: 8,
            StateVersion: 42,
            PayloadSha256: new string('a', 64),
            IdempotencyKey: "idem-000001",
            EffectRisk: "READ_ONLY",
            ApprovalRef: null,
            IssuedAt: "2026-11-21T12:00:00.000Z",
            ExpiresAt: "2026-11-21T12:05:00.000Z",
            SigningKeyEpoch: 3,
            SigningPublicKeySha256: new string('b', 64),
            SignatureAlgorithm: CoreOwnershipTransferCryptography.Algorithm,
            SignatureFormat: CoreOwnershipTransferCryptography.SignatureFormat);

        const string expected =
            "1:113:transfer-000112:nonce-00000111:task-00000112:project-000115:android-core-0115:desktop-core-011:71:82:4264:" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa11:idem-0000019:READ_ONLY4:null" +
            "24:2026-11-21T12:00:00.000Z24:2026-11-21T12:05:00.000Z1:364:" +
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "17:ECDSA_P256_SHA25619:IEEE_P1363_FIXED_64";

        Assert.Equal(expected, Encoding.UTF8.GetString(
            CoreOwnershipTransferCryptography.GetCanonicalBytes(payload)));
    }

    [Fact]
    public void P256SignatureVerifiesAndIsBoundToPayloadAndPublicKeyFingerprint()
    {
        using var signer = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = signer.ExportSubjectPublicKeyInfo();
        var fingerprint = Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant();
        var payload = CreatePayload(fingerprint);

        var signature = CoreOwnershipTransferCryptography.Sign(signer, payload);

        Assert.Equal(64, signature.Length);
        Assert.True(CoreOwnershipTransferCryptography.Verify(payload, publicKey, signature));
        Assert.False(CoreOwnershipTransferCryptography.Verify(
            payload with { StateVersion = payload.StateVersion + 1 }, publicKey, signature));

        using var otherKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        Assert.False(CoreOwnershipTransferCryptography.Verify(
            payload, otherKey.ExportSubjectPublicKeyInfo(), signature));
        Assert.False(CoreOwnershipTransferCryptography.Verify(
            payload with { SigningPublicKeySha256 = new string('0', 64) }, publicKey, signature));
    }

    [Fact]
    public void InvalidOwnerGenerationAndMissingHighRiskApprovalFailBeforeSigning()
    {
        using var signer = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = signer.ExportSubjectPublicKeyInfo();
        var fingerprint = Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant();
        var payload = CreatePayload(fingerprint);

        Assert.Throws<ArgumentException>(() =>
            CoreOwnershipTransferCryptography.GetCanonicalBytes(
                payload with { NewOwnerGeneration = payload.OldOwnerGeneration + 2 }));
        Assert.Throws<ArgumentException>(() =>
            CoreOwnershipTransferCryptography.GetCanonicalBytes(
                payload with { NewOwnerCoreId = payload.OldOwnerCoreId }));
        Assert.Throws<ArgumentException>(() =>
            CoreOwnershipTransferCryptography.GetCanonicalBytes(
                payload with { EffectRisk = "FINANCIAL", ApprovalRef = null }));
    }

    private static OwnershipTransferSigningPayload CreatePayload(string fingerprint) =>
        new(
            SchemaVersion: 1,
            TransferId: "transfer-0001",
            Nonce: "nonce-000001",
            TaskId: "task-000001",
            ProjectId: "project-0001",
            OldOwnerCoreId: "android-core-01",
            NewOwnerCoreId: "desktop-core-01",
            OldOwnerGeneration: 7,
            NewOwnerGeneration: 8,
            StateVersion: 42,
            PayloadSha256: new string('a', 64),
            IdempotencyKey: "idem-000001",
            EffectRisk: "READ_ONLY",
            ApprovalRef: null,
            IssuedAt: "2026-11-21T12:00:00.000Z",
            ExpiresAt: "2026-11-21T12:05:00.000Z",
            SigningKeyEpoch: 3,
            SigningPublicKeySha256: fingerprint,
            SignatureAlgorithm: CoreOwnershipTransferCryptography.Algorithm,
            SignatureFormat: CoreOwnershipTransferCryptography.SignatureFormat);
}
