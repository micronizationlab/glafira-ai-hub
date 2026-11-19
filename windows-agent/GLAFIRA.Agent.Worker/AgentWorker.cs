namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Windows background-service shell. This first increment intentionally does not
/// connect to a host or claim work before owner-approved enrollment is implemented.
/// </summary>
public sealed class AgentWorker(
    ILogger<AgentWorker> logger,
    AgentRuntimeState runtimeState) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        runtimeState.SetStatus(AgentServiceStatus.AwaitingOwnerEnrollment);
        logger.LogInformation(
            "GLAFIRA Agent service started; awaiting owner-approved enrollment.");

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
