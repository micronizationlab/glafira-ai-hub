using GLAFIRA.Agent.Worker;
using Microsoft.Extensions.Hosting;

if (args.Length == 1 && StringComparer.Ordinal.Equals(args[0], "--pair"))
{
    try
    {
        await AgentPairingWizard.RunAsync();
    }
    catch (Exception error)
    {
        Console.Error.WriteLine($"Сопряжение не выполнено: {error.Message}");
        Environment.ExitCode = 1;
    }
    return;
}

var builder = Host.CreateApplicationBuilder(args);

builder.Services.AddWindowsService(options =>
{
    options.ServiceName = "GLAFIRA Agent";
});

builder.Services.Configure<HostOptions>(options =>
{
    options.ShutdownTimeout = TimeSpan.FromSeconds(30);
});

builder.Services.AddSingleton<AgentRuntimeState>();
builder.Services.AddSingleton<AgentTaskHandlerRegistry>();
builder.Services.AddSingleton<IAgentCredentialStore, DpapiAgentCredentialStore>();
builder.Services.AddSingleton<AgentPairingClient>();
builder.Services.AddSingleton<BridgeV1AgentClient>();
builder.Services.AddHostedService<AgentWorker>();

await builder.Build().RunAsync();
