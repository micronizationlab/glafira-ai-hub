using System.Collections.Frozen;

namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Canonical Bridge v1 enrollment scopes. Pairing may only grant scopes that
/// the request authenticator can enforce; persisted grants are revalidated on
/// every read so an unknown or non-canonical value fails closed.
/// </summary>
public static class BridgeV1ScopePolicy
{
    private static readonly IReadOnlyDictionary<BridgeV1Operation, string> OperationScopes =
        new Dictionary<BridgeV1Operation, string>
        {
            [BridgeV1Operation.PROJECT_READ] = "project.read",
            [BridgeV1Operation.CONVERSATION_READ] = "conversation.read",
            [BridgeV1Operation.CONVERSATION_APPEND] = "conversation.append",
            [BridgeV1Operation.APPROVAL_REQUEST] = "approval.request",
            [BridgeV1Operation.OWNERSHIP_TRANSFER_DISCOVER] = "ownership.read",
            [BridgeV1Operation.OWNERSHIP_TRANSFER_READ] = "ownership.read",
            [BridgeV1Operation.DESKTOP_TASK_CLAIM] = "desktop.task.claim",
            [BridgeV1Operation.DESKTOP_TASK_RESULT] = "desktop.task.result",
            [BridgeV1Operation.DESKTOP_TASK_ARTIFACT] = "desktop.task.result",
            [BridgeV1Operation.DESKTOP_AGENT_HEARTBEAT] = "desktop.task.claim"
        };

    public static IReadOnlySet<string> SupportedScopes { get; } =
        OperationScopes.Values.ToFrozenSet(StringComparer.Ordinal);

    public static string RequiredFor(BridgeV1Operation operation) =>
        OperationScopes.TryGetValue(operation, out var scope)
            ? scope
            : throw new ArgumentOutOfRangeException(nameof(operation), operation, "Unknown Bridge v1 operation.");

    internal static string[] NormalizeEnrollmentScopes(
        IEnumerable<string> scopes,
        string parameterName)
    {
        ArgumentNullException.ThrowIfNull(scopes, parameterName);
        var values = scopes.ToArray();
        if (values.Length is < 1 or > 32 ||
            values.Any(scope => string.IsNullOrWhiteSpace(scope) || scope.Length > 128 ||
                !scope.All(character => char.IsAsciiLetterOrDigit(character) || character is '.' or '_' or ':' or '-')) ||
            values.Distinct(StringComparer.Ordinal).Count() != values.Length)
            throw new ArgumentException(
                "Scopes must be 1 to 32 unique, bounded ASCII scope identifiers.",
                parameterName);

        if (values.Any(scope => !SupportedScopes.Contains(scope)))
            throw new ArgumentException(
                "Scopes must be supported Bridge v1 operation scopes.",
                parameterName);

        return values.Order(StringComparer.Ordinal).ToArray();
    }
}
