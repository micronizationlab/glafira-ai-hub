using System.Security.Cryptography;
using System.Runtime.Versioning;

namespace GLAFIRA.Desktop.Hub.Core;

public interface IHubDataProtector
{
    byte[] Protect(ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> entropy);
    byte[] Unprotect(ReadOnlySpan<byte> ciphertext, ReadOnlySpan<byte> entropy);
}

/// <summary>
/// Protects durable Hub task results with the current Windows user's DPAPI key.
/// The Desktop Hub must run under a stable Windows identity; ciphertext is not
/// portable to another Windows account or machine.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsDpapiHubDataProtector : IHubDataProtector
{
    public byte[] Protect(ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> entropy)
    {
        EnsureWindows();
        var input = plaintext.ToArray();
        var optionalEntropy = entropy.ToArray();
        try
        {
            return ProtectedData.Protect(input, optionalEntropy, DataProtectionScope.CurrentUser);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(input);
            CryptographicOperations.ZeroMemory(optionalEntropy);
        }
    }

    public byte[] Unprotect(ReadOnlySpan<byte> ciphertext, ReadOnlySpan<byte> entropy)
    {
        EnsureWindows();
        var input = ciphertext.ToArray();
        var optionalEntropy = entropy.ToArray();
        try
        {
            return ProtectedData.Unprotect(input, optionalEntropy, DataProtectionScope.CurrentUser);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(input);
            CryptographicOperations.ZeroMemory(optionalEntropy);
        }
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Windows DPAPI is required for persistent Hub task results.");
    }
}
