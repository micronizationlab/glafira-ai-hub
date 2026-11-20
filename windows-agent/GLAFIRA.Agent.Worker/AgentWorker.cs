using System.Security.Cryptography;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Windows background-service shell. It validates the machine-protected enrollment
/// at startup but does not claim work until the authenticated Agent runtime is implemented.
/// </summary>
public sealed class AgentWorker(
    ILogger<AgentWorker> logger,
    AgentRuntimeState runtimeState,
    IAgentCredentialStore credentialStore) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            var credential = await credentialStore.LoadAsync(stoppingToken).ConfigureAwait(false);
            if (credential is null)
            {
                runtimeState.SetStatus(AgentServiceStatus.AwaitingOwnerEnrollment);
                logger.LogInformation(
                    "GLAFIRA Agent service started; no machine-protected enrollment is present.");
            }
            else
            {
                runtimeState.SetStatus(AgentServiceStatus.Enrolled);
                logger.LogInformation(
                    "GLAFIRA Agent {AgentId} has a valid machine-protected enrollment for Hub {HubId}; task polling remains disabled in this build.",
                    credential.AgentId, credential.DesktopHubId);
            }
        }
        catch (Exception error) when (error is InvalidDataException or CryptographicException or IOException)
        {
            runtimeState.SetStatus(AgentServiceStatus.Faulted);
            logger.LogError(error, "GLAFIRA Agent enrollment could not be loaded; failing closed.");
            return;
        }

        try
        {
            using var timer = new PeriodicTimer(TimeSpan.FromSeconds(30));
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                logger.LogDebug(
                    "GLAFIRA Agent service is {AgentStatus}; no task polling is enabled.",
                    runtimeState.Status);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // Normal Windows service shutdown.
        }
        finally
        {
            runtimeState.SetStatus(AgentServiceStatus.Stopped);
            logger.LogInformation("GLAFIRA Agent service stopped.");
        }
    }
}
