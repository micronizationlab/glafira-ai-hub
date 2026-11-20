using GLAFIRA.Agent.Worker;
using Microsoft.Extensions.Hosting;

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
builder.Services.AddHostedService<AgentWorker>();

await builder.Build().RunAsync();
