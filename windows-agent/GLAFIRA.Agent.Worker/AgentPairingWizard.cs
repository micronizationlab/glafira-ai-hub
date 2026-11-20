using System.Security.Principal;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Interactive one-time pairing command. The shared code is read masked and is
/// never accepted from process arguments, environment variables or log messages.
/// Credential persistence uses the same machine DPAPI/ACL vault as the LocalSystem service.
/// </summary>
public static class AgentPairingWizard
{
    public static async Task RunAsync(CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("GLAFIRA Agent pairing is Windows-only.");

        using var identity = WindowsIdentity.GetCurrent();
        var principal = new WindowsPrincipal(identity);
        if (!principal.IsInRole(WindowsBuiltInRole.Administrator))
            throw new UnauthorizedAccessException(
                "Запустите сопряжение от имени администратора: запись защищённого хранилища требует elevation.");

        Console.WriteLine("GLAFIRA Agent — одноразовое сопряжение с Desktop Hub");
        Console.WriteLine("Код действует 5 минут. Никому не отправляйте его кроме подключаемого компьютера.");
        var endpointText = ReadRequired("HTTPS endpoint (https://IP:8443/bridge/v1): ");
        var pin = ReadRequired("TLS SHA-256 fingerprint (64 lowercase hex): ").ToLowerInvariant();
        var ticketId = ReadRequired("Ticket ID: ").ToLowerInvariant();
        var pairingCode = ReadMasked("Одноразовый pairing code: ");
        var defaultAgentId = "agent-" + new string(Environment.MachineName
            .ToLowerInvariant()
            .Where(character => char.IsAsciiLetterOrDigit(character) || character == '-')
            .Take(110)
            .ToArray());
        var agentId = ReadOptional($"Agent ID [{defaultAgentId}]: ");
        if (string.IsNullOrWhiteSpace(agentId)) agentId = defaultAgentId;

        if (!Uri.TryCreate(endpointText, UriKind.Absolute, out var endpoint))
            throw new ArgumentException("HTTPS endpoint is invalid.");

        var attempt = new AgentPairingAttempt(endpoint, pin, ticketId, pairingCode, agentId);
        var credentialStore = new DpapiAgentCredentialStore();
        var credential = await new AgentPairingClient(credentialStore)
            .EnrollAsync(attempt, cancellationToken).ConfigureAwait(false);

        Console.WriteLine();
        Console.WriteLine($"Сопряжение завершено: Agent {credential.AgentId} → Hub {credential.DesktopHubId}.");
        Console.WriteLine($"Credential сохранён в машинном DPAPI-хранилище; истекает {credential.ExpiresAt.ToLocalTime():dd.MM.yyyy HH:mm}.");
        Console.WriteLine("Теперь подтвердите Agent и назначьте ему capabilities в GLAFIRA Desktop.");
        Console.WriteLine("После этого перезапустите службу GLAFIRA Agent. Исполнение задач пока не включено в этой версии.");
    }

    private static string ReadRequired(string prompt)
    {
        var value = ReadOptional(prompt);
        if (string.IsNullOrWhiteSpace(value))
            throw new ArgumentException("Обязательное значение не введено.");
        return value.Trim();
    }

    private static string ReadOptional(string prompt)
    {
        Console.Write(prompt);
        return Console.ReadLine()?.Trim() ?? string.Empty;
    }

    private static string ReadMasked(string prompt)
    {
        Console.Write(prompt);
        var value = new List<char>();
        while (true)
        {
            var key = Console.ReadKey(intercept: true);
            if (key.Key == ConsoleKey.Enter) break;
            if (key.Key == ConsoleKey.Backspace)
            {
                if (value.Count > 0)
                {
                    value.RemoveAt(value.Count - 1);
                    Console.Write("\b \b");
                }
                continue;
            }
            if (!char.IsControl(key.KeyChar) && value.Count < 128)
            {
                value.Add(key.KeyChar);
                Console.Write('*');
            }
        }
        Console.WriteLine();
        var result = new string(value.ToArray());
        Array.Clear(value.ToArray());
        return result;
    }
}
