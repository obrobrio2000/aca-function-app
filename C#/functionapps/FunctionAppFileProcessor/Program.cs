using Azure.Identity;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Azure.Functions.Worker.Builder;
using Microsoft.Extensions.Azure;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

var builder = FunctionsApplication.CreateBuilder(args);

builder.ConfigureFunctionsWebApplication();

// Application Insights isn't enabled by default. See https://aka.ms/AAt8mw4.
builder.Services
    .AddApplicationInsightsTelemetryWorkerService()
    .ConfigureFunctionsApplicationInsights();

//Remove the default Application Insights logging filter rule to allow all log levels
builder.Logging.Services.Configure<LoggerFilterOptions>(options =>
{
    LoggerFilterRule? defaultRule = options.Rules.FirstOrDefault(rule => rule.ProviderName
        == "Microsoft.Extensions.Logging.ApplicationInsights.ApplicationInsightsLoggerProvider");
    if (defaultRule is not null)
    {
        options.Rules.Remove(defaultRule);
    }
});

// Register BlobServiceClient for blob storage access
builder.Services.AddAzureClients(clientBuilder =>
{
    // Source storage account (for reading CSV)
    clientBuilder.AddBlobServiceClient(new Uri(builder.Configuration["FileStorageAccountEndpoint"]!))
        .WithName("SourceBlobServiceClient")
        .WithCredential(new DefaultAzureCredential());
    
    // Destination storage account (for writing filtered CSV)
    clientBuilder.AddBlobServiceClient(new Uri(builder.Configuration["DestinationStorageAccountEndpoint"]!))
        .WithName("DestinationBlobServiceClient")
        .WithCredential(new DefaultAzureCredential());

    clientBuilder.AddEventHubProducerClientWithNamespace(builder.Configuration["EventHubNamespace"]!, builder.Configuration["EventHubName"]!)
        .WithName("EventHubProducerClient")
        .WithCredential(new DefaultAzureCredential());  
});

builder.Build().Run();