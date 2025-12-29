using Azure.Messaging.EventGrid;
using Azure.Messaging.EventGrid.SystemEvents;
using Azure.Messaging.EventHubs;
using Azure.Messaging.EventHubs.Producer;
using Azure.Messaging.ServiceBus;
using Azure.Storage.Blobs;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Azure;
using Microsoft.Extensions.Logging;
using System.Text;


namespace FunctionAppFileProcessor
{
    public class ServiceBusFunction
    {
        private readonly ILogger<ServiceBusFunction> _logger;
        private readonly BlobServiceClient _sourceBlobServiceClient;
        private readonly BlobServiceClient _destinationBlobServiceClient;
        private readonly EventHubProducerClient _eventHubProducerClient;

        public ServiceBusFunction(
            ILogger<ServiceBusFunction> logger, 
            IAzureClientFactory<BlobServiceClient> blobClientFactory,
            IAzureClientFactory<EventHubProducerClient> eventHubClientFactory)
        {
            _logger = logger;
            _sourceBlobServiceClient = blobClientFactory.CreateClient("SourceBlobServiceClient");
            _destinationBlobServiceClient = blobClientFactory.CreateClient("DestinationBlobServiceClient");
            _eventHubProducerClient = eventHubClientFactory.CreateClient("EventHubProducerClient");
        }

        [Function(nameof(ServiceBusFunction))]
        public async Task Run(
            [ServiceBusTrigger("queue-blob-created", Connection = "ConnToServiceBusFile")]
            ServiceBusReceivedMessage message,
            ServiceBusMessageActions messageActions)
        {
            _logger.LogInformation("Message ID: {id}", message.MessageId);
            _logger.LogInformation("Message Body: {body}", message.Body);
            _logger.LogInformation("Message Content-Type: {contentType}", message.ContentType);

            try
            {
                // Parse l'evento Event Grid dal corpo del messaggio Service Bus
                var eventGridEvent = EventGridEvent.Parse(message.Body);
                _logger.LogInformation("Event Type: {eventType}", eventGridEvent.EventType);
                _logger.LogInformation("Subject: {subject}", eventGridEvent.Subject);

                // Verifica che sia un evento di blob creato
                if (eventGridEvent.EventType == "Microsoft.Storage.BlobCreated")
                {
                    // Deserializza i dati dell'evento in StorageBlobCreatedEventData
                    var blobCreatedData = eventGridEvent.Data?.ToObjectFromJson<StorageBlobCreatedEventData>();
                    
                    if (blobCreatedData == null)
                    {
                        _logger.LogWarning("Failed to deserialize BlobCreated event data");
                        return;
                    }
                    
                    _logger.LogInformation("Blob URL: {url}", blobCreatedData.Url);
                    _logger.LogInformation("Blob API: {api}", blobCreatedData.Api);
                    _logger.LogInformation("Content Type: {contentType}", blobCreatedData.ContentType);
                    _logger.LogInformation("Content Length: {contentLength}", blobCreatedData.ContentLength);

                    // Filtra solo gli eventi SftpCommit per evitare elaborazioni duplicate, SftpCreate viene generato all'inizio del caricamento (file potenzialmente incompleto), SftpCommit viene generato quando il file è completamente caricato
                    if (blobCreatedData.Api != "SftpCommit")
                    {
                        _logger.LogInformation("Ignoring event with API: {api} - only processing SftpCommit", blobCreatedData.Api);
                        await messageActions.CompleteMessageAsync(message);
                        return;
                    }

                    // Estrae il nome del container e del blob dall'URL
                    var blobUri = new Uri(blobCreatedData.Url);
                    var pathSegments = blobUri.AbsolutePath.TrimStart('/').Split('/', 2);
                    
                    if (pathSegments.Length >= 2)
                    {
                        var containerName = pathSegments[0];
                        var blobName = pathSegments[1];
                        
                        _logger.LogInformation("Container: {container}, Blob: {blob}", containerName, blobName);

                        await ProcessBlobAsync(containerName, blobName);
                    }
                    else
                    {
                        _logger.LogWarning("Unable to parse blob path from URL: {url}", blobCreatedData.Url);
                    }
                }
                else
                {
                    _logger.LogWarning("Received non-BlobCreated event: {eventType}", eventGridEvent.EventType);
                }

                // Complete the message
                await messageActions.CompleteMessageAsync(message);
                _logger.LogInformation("Successfully processed blob and completed message");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error processing blob from Service Bus message");
                // Optionally: await messageActions.DeadLetterMessageAsync(message);
                throw;
            }
        }

        private async Task ProcessBlobAsync(string containerName, string blobName)
        {
            try
            {
                var sourceBlobContainerClient = _sourceBlobServiceClient.GetBlobContainerClient(containerName);
                var sourceBlobClient = sourceBlobContainerClient.GetBlobClient(blobName);

                if (!await sourceBlobClient.ExistsAsync())
                {
                    _logger.LogWarning("Source blob does not exist: {containerName}/{blobName}", containerName, blobName);
                    return;
                }

                var properties = await sourceBlobClient.GetPropertiesAsync();
                _logger.LogInformation("Processing Blob. Size: {size} bytes", properties.Value.ContentLength);

                // ====================================================================
                // ESEMPIO 1: Lettura file come stream (senza scaricarlo localmente)
                // ====================================================================
                using var sourceStream = await sourceBlobClient.OpenReadAsync();
                using var reader = new StreamReader(sourceStream);
                // TODO: Aggiungere/modificare la logica di lettura del file

                // ====================================================================
                // ESEMPIO 2: Creazione nuovo file di output su storage account diverso
                // ====================================================================
                var destinationContainerName = "filtered-csv";
                var destinationBlobName = $"{DateTime.UtcNow:yyyyMMddHHmmss}_new_{blobName}";
                var destinationBlobContainerClient = _destinationBlobServiceClient.GetBlobContainerClient(destinationContainerName);
                await destinationBlobContainerClient.CreateIfNotExistsAsync();
                
                _logger.LogInformation("Destination blob: {containerName}/{blobName}", destinationContainerName, destinationBlobName);
                
                var destinationBlobClient = destinationBlobContainerClient.GetBlobClient(destinationBlobName);
                using var outputStream = new MemoryStream();
                using var writer = new StreamWriter(outputStream, Encoding.UTF8, leaveOpen: true);
                // TODO: Aggiungere/modificare la logica di scrittura del file

                // ====================================================================
                // ESEMPIO 3: Invio evento JSON su Azure Event Hub
                // ====================================================================
                var eventData = new
                {
                    ProcessedAt = DateTime.UtcNow,
                    SourceBlobName = blobName,
                    Field1 = "Foo",
                    Field2 = "Bar"
                };

                var jsonEvent = System.Text.Json.JsonSerializer.Serialize(eventData);
                var eventDataBatch = await _eventHubProducerClient.CreateBatchAsync();
                
                if (!eventDataBatch.TryAdd(new EventData(Encoding.UTF8.GetBytes(jsonEvent))))
                {
                    _logger.LogError("Event is too large for the batch");
                }
                else
                {
                    await _eventHubProducerClient.SendAsync(eventDataBatch);
                    _logger.LogInformation("Event sent to Event Hub: {jsonEvent}", jsonEvent);
                }
                // TODO: Aggiungere/modificare la logica di invio evento

            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error processing File Blob: {containerName}/{blobName}", containerName, blobName);
                throw;
            }
        }
    }
 
}
