using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace GLAFIRA.Desktop.Core.Ownership;

/// <summary>
/// Signature-covered fields of the v1 ownership transfer envelope.
/// The signature itself is intentionally excluded from this record.
/// </summary>
public sealed record OwnershipTransferSigningPayload(
    int SchemaVersion,
    string TransferId,
    string Nonce,
    string TaskId,
    string ProjectId,
    string OldOwnerCoreId,
    string NewOwnerCoreId,
    long OldOwnerGeneration,
    long NewOwnerGeneration,
    long StateVersion,
    string PayloadSha256,
    string IdempotencyKey,
    string EffectRisk,
    string? ApprovalRef,
    string IssuedAt,
    string ExpiresAt,
    long SigningKeyEpoch,
    string SigningPublicKeySha256,
    string SignatureAlgorithm,
    string SignatureFormat);

/// <summary>
/// Cross-platform canonicalization and ECDSA helpers for Dual-Core Ownership Transfer v1.
/// Trust-anchor lookup and owner revocation checks remain the caller's responsibility.
/// </summary>
public static class CoreOwnershipTransferCryptography
{
    public const string Algorithm = "ECDSA_P256_SHA256";
    public const string SignatureFormat = "IEEE_P1363_FIXED_64";

    private static readonly Regex IdentifierPattern =
        new("^[A-Za-z0-9._-]{8,128}$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex Sha256Pattern =
        new("^[a-f0-9]{64}$", RegexOptions.CultureInvariant | RegexOptions.Compiled);

    /// <summary>
    /// Returns UTF-8 bytes of the v1 fixed-order, UTF-16-length-prefixed canonical payload.
    /// Do not serialize this record as JSON for signing; use these exact bytes.
    /// </summary>
    public static byte[] GetCanonicalBytes(OwnershipTransferSigningPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        Validate(payload);

        string?[] values =
        [
            payload.SchemaVersion.ToString(CultureInfo.InvariantCulture),
            payload.TransferId,
            payload.Nonce,
            payload.TaskId,
            payload.ProjectId,
            payload.OldOwnerCoreId,
            payload.NewOwnerCoreId,
            payload.OldOwnerGeneration.ToString(CultureInfo.InvariantCulture),
            payload.NewOwnerGeneration.ToString(CultureInfo.InvariantCulture),
            payload.StateVersion.ToString(CultureInfo.InvariantCulture),
            payload.PayloadSha256,
            payload.IdempotencyKey,
            payload.EffectRisk,
            payload.ApprovalRef,
            payload.IssuedAt,
            payload.ExpiresAt,
            payload.SigningKeyEpoch.ToString(CultureInfo.InvariantCulture),
            payload.SigningPublicKeySha256,
            payload.SignatureAlgorithm,
            payload.SignatureFormat
        ];

        var canonical = new StringBuilder();
        foreach (var value in values)
        {
            var encoded = value ?? "null";
            canonical.Append(encoded.Length.ToString(CultureInfo.InvariantCulture));
            canonical.Append(':');
            canonical.Append(encoded);
        }

        return Encoding.UTF8.GetBytes(canonical.ToString());
    }

    public static byte[] Sign(ECDsa signer, OwnershipTransferSigningPayload payload)
    {
        ArgumentNullException.ThrowIfNull(signer);
        if (signer.KeySize != 256)
            throw new CryptographicException("OWNERSHIP_SIGNING_KEY_MUST_BE_P256");

        var publicKey = signer.ExportSubjectPublicKeyInfo();
        var fingerprint = Convert.ToHexString(SHA256.HashData(publicKey)).ToLowerInvariant();
        if (!FixedTimeEqualsAscii(fingerprint, payload.SigningPublicKeySha256))
            throw new CryptographicException("OWNERSHIP_SIGNING_KEY_FINGERPRINT_MISMATCH");

        return signer.SignData(
            GetCanonicalBytes(payload),
            HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
    }

    /// <summary>
    /// Verifies key fingerprint and P1363 signature. The caller must separately establish
    /// that this public key is the trusted, non-revoked key for OldOwnerCoreId/KeyEpoch.
    /// </summary>
    public static bool Verify(
        OwnershipTransferSigningPayload payload,
        ReadOnlySpan<byte> publicKeySubjectPublicKeyInfo,
        ReadOnlySpan<byte> signature)
    {
        ArgumentNullException.ThrowIfNull(payload);
        try
        {
            if (signature.Length != 64 || publicKeySubjectPublicKeyInfo.IsEmpty)
                return false;

            var fingerprint = Convert.ToHexString(
                SHA256.HashData(publicKeySubjectPublicKeyInfo)).ToLowerInvariant();
            if (!FixedTimeEqualsAscii(fingerprint, payload.SigningPublicKeySha256))
                return false;

            using var verifier = ECDsa.Create();
            verifier.ImportSubjectPublicKeyInfo(publicKeySubjectPublicKeyInfo, out var consumed);
            if (consumed != publicKeySubjectPublicKeyInfo.Length || verifier.KeySize != 256)
                return false;

            return verifier.VerifyData(
                GetCanonicalBytes(payload),
                signature,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        catch (Exception ex) when (ex is CryptographicException or ArgumentException or FormatException)
        {
            return false;
        }
    }

    private static void Validate(OwnershipTransferSigningPayload payload)
    {
        if (payload.SchemaVersion != 1)
            throw new ArgumentException("OWNERSHIP_TRANSFER_SCHEMA_VERSION_UNSUPPORTED");
        foreach (var identifier in new[]
        {
            payload.TransferId, payload.Nonce, payload.TaskId, payload.ProjectId,
            payload.OldOwnerCoreId, payload.NewOwnerCoreId, payload.IdempotencyKey
        })
        {
            if (!IdentifierPattern.IsMatch(identifier))
                throw new ArgumentException("OWNERSHIP_TRANSFER_IDENTIFIER_INVALID");
        }

        if (StringComparer.Ordinal.Equals(payload.OldOwnerCoreId, payload.NewOwnerCoreId))
            throw new ArgumentException("OWNERSHIP_TRANSFER_OWNER_MUST_CHANGE");
        if (payload.OldOwnerGeneration < 1 ||
            payload.OldOwnerGeneration == long.MaxValue ||
            payload.NewOwnerGeneration != payload.OldOwnerGeneration + 1)
            throw new ArgumentException("OWNERSHIP_TRANSFER_GENERATION_NOT_NEXT");
        if (payload.StateVersion < 0 || payload.SigningKeyEpoch < 1)
            throw new ArgumentException("OWNERSHIP_TRANSFER_VERSION_INVALID");
        if (!Sha256Pattern.IsMatch(payload.PayloadSha256) ||
            !Sha256Pattern.IsMatch(payload.SigningPublicKeySha256))
            throw new ArgumentException("OWNERSHIP_TRANSFER_DIGEST_INVALID");
        if (payload.SignatureAlgorithm != Algorithm || payload.SignatureFormat != SignatureFormat)
            throw new ArgumentException("OWNERSHIP_TRANSFER_SIGNATURE_FORMAT_UNSUPPORTED");

        if (!DateTimeOffset.TryParse(payload.IssuedAt, CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var issuedAt) ||
            !DateTimeOffset.TryParse(payload.ExpiresAt, CultureInfo.InvariantCulture,
                DateTimeStyles.RoundtripKind, out var expiresAt) ||
            expiresAt <= issuedAt)
            throw new ArgumentException("OWNERSHIP_TRANSFER_TIME_WINDOW_INVALID");

        if (payload.EffectRisk is not ("READ_ONLY" or "DRAFT" or "EXTERNAL_SIDE_EFFECT" or "FINANCIAL" or "DESTRUCTIVE"))
            throw new ArgumentException("OWNERSHIP_TRANSFER_EFFECT_RISK_INVALID");
        if (payload.EffectRisk is "EXTERNAL_SIDE_EFFECT" or "FINANCIAL" or "DESTRUCTIVE")
        {
            if (payload.ApprovalRef is null || !IdentifierPattern.IsMatch(payload.ApprovalRef))
                throw new ArgumentException("OWNERSHIP_TRANSFER_APPROVAL_REQUIRED");
        }
        else if (payload.ApprovalRef is not null && !IdentifierPattern.IsMatch(payload.ApprovalRef))
        {
            throw new ArgumentException("OWNERSHIP_TRANSFER_APPROVAL_INVALID");
        }
    }

    private static bool FixedTimeEqualsAscii(string left, string right)
    {
        var leftBytes = Encoding.ASCII.GetBytes(left);
        var rightBytes = Encoding.ASCII.GetBytes(right);
        return leftBytes.Length == rightBytes.Length &&
            CryptographicOperations.FixedTimeEquals(leftBytes, rightBytes);
    }
}
