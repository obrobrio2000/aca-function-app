# Function APP in Azure Container App per "File FTP"

Questo progetto contiene esempi di Azure Functions containerizzate per l'elaborazione di file caricati via SFTP, disponibili in due implementazioni per i linguaggi C# e Java presenti nelle rispettive cartelle.

La struttura delle cartelle è la seguente:
- Cartella `deploy`, con i file necessari per il deploy su Azure, in particolare:
    - File `01_BuildImage.ps1`, avvia la build dell'immagine del container dela Function App
    - File `02_DeployToAca.ps1`, avvia il deploy della Function App in Azure Container App
    - File `container-app.yaml`, descrittore della configurazione della Function App in Azure Container App.

    Alcuni file andranno rivisti per modificare i valori del proprio tenant (e.g. nome risorse, client id, etc.)
- Cartella `functionapps`, con esempi di Function App, in particolare:
    - Cartella "FunctionAppFileProcessor", con il codice demo per una Azure Function con trigger Service Bus che legge il File da storage account e ne scrive uno nuovo sempre su storage account o eventualmente evento su Event Hub.

    Alcuni file (e.g. local.setting.json) andranno rivisti per lo sviluppo locale per modificare i valori del proprio tenant (e.g. nome risorse, client id, etc.)

## Architettura Azure 
L’architettura Azure è strutturata secondo il seguente flusso:
1) Storage Account con endpoint FTP utilizzato per il caricamento dei file.
2) Event Grid configurato per ricevere gli eventi BlobCreated dallo Storage Account e rilevare in modo reattivo la creazione di nuovi file.
3) Gli eventi intercettati vengono inviati a una coda di Service Bus, utilizzata per garantire retention ed elaborazione affidabile.
4) Una Azure Function con trigger Service Bus elabora i messaggi relativi ai file creati e avvia il workflow di processamento.
5) L’output dell’elaborazione viene scritto come file in un secondo Storage Account oppure inviato come eventi a Event Hub, nel caso di scenari di streaming.

### Utenza Personale e Permessi Azure
Quando si sviluppa e per operare sulle risorse menzionate occorre che la propria utenza abbia i seguenti permessi:
- Storage Blob Data Contributor sullo Storage Account
- Azure Service Bus Data Receiver su Service Bus
- Azure Event Hubs Data Sender su Event Hub
- AcrPush su Azure Container Registry
- Container Apps Contributor sul resource group del Container App Environment
- Managed Identity Operator sul resource goup (per associare alla Function, la Managed Identity descritta in basso)

Il codice delle function utilizza una managed identity per connettersi ai servizi Azure utilizzati, evitando di utilizzare secret. Quando si sviluppa in locale, invece della managed identity verrà utilizzata la propria utenza personale.

NOTA:
Per configurare la sottoscrizione Event Grid sullo Storage Account (operazione una tantum) è necessario disporre del ruolo Contributor sulle risorse coinvolte. Se questo ruolo non può essere assegnato, la configurazione deve essere richiesta a chi possiede già i permessi appropriati.


### User Assigned Managed Identity e Permessi Azure
La Function App in esecuzione su Azure, dopo il deploy, utilizzerà una Managed Identity per accedere alle risorse Azure necessario per l'operatività. Questa identità deve esistere prima che l'applicazione venga deployata e quindi necessario richiedere la creazione di una User Assigned Managed Identity con i seguenti permessi:
- Storage Blob Data Contributor sullo Storage Account
- Azure Service Bus Data Receiver su Service Bus
- Azure Event Hubs Data Sender su Event Hub

Questa identità sarà associata alla function app durante il deploy.

## Sviluppo in locale

### Prerequisiti
Per sviluppare in locale è necessario avere i seguenti prerequisiti:
- Azure Function Core Tools (4.6.0) - https://learn.microsoft.com/en-us/azure/azure-functions/functions-run-local
- .NET SDK 8 - https://dotnet.microsoft.com/en-us/download/dotnet/8.0
- Java 21 + Maven
- Azure CLI (2.81) - https://learn.microsoft.com/en-us/cli/azure/install-azure-cli-windows
- Azurite (emulatore storage account) - https://learn.microsoft.com/en-us/azure/storage/common/storage-install-azurite
- Visual Studio Code (per Java/.NET). Estensioni consigliate: 
    - C# Dev Kit - https://marketplace.visualstudio.com/items?itemName=ms-dotnettools.csdevkit
    - Extension Pack for Java - https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack
    - Azure Functon - https://marketplace.visualstudio.com/items?itemName=ms-azuretools.vscode-azurefunctions
- Visual Studio 2026 (opzionale per migliore esperienza con .NET)

Per istruzioni su come lavorare in sviluppo con le function:
https://learn.microsoft.com/en-us/azure/azure-functions/functions-develop-vs-code


### Debug
E' possibile avviare l'editing della Function App e quindi il debug in VS Code nel modo seguente:
1) Effettuare il login con la Azure CLI:
    ``` pwsh 
    az login
    az account set -s <subscrpiontID>
    ```
2) Aprire la cartella della Function App in VS Code (e.g. `./C#/functionapps/FunctionAppFileProcessor`)
3) Se non già creato, duplicare il file `local.settings.example.json` e rinominarlo in `local.settings.json` sostituendo tutti i valori dei `<PLACEHOLDER>` con quelli delle risorse di destinazione
4) Verificare il contenuto di partenza della function demo (`ServiceBusFunction.java` o `ServiceBusFunction.cs`)
5) Avviare il debug (F5), sono già presenti nella cartella `.vscode` le impostazioni per il debug nei 2 linguaggi

NOTA: Per una migliore esperienza con .NET è possibile utilizzare Visual Studio 2026 ed aprire il file di soluzione (`FunctionAppFileProcessor.sln`) invece che la cartella


### Implementazione
La function demo (ServiceBusFunction) in entrambi i lunguaggi è strutturata per separare il codice di gestione e parsing dell'evento dal processamento del file. Infatti è possibile concentrarsi solo sul metodo `processBlobAsync` ed aggiungere la logica di gestione del formato file specifico.

Di fatto questa funzione riceve in input:
- containerName, il container dello storage account (cartella di primo livello) dove è presente il file modiifcato
- blobName, il nome del file modificato (sono anteposte ventuali sottocartelle)

Nell'implementazione di demo sono mostrati 3 snippet di codice di esempio per:
1) Leggere il file in streaming, senza quindi scaricarlo localmente
2) Creare un nuovo file su storage account (eventuale risultato dell'elaborazione)
3) Inviare un evento sull'EventHub (per approccio straming)

NOTA: Questa segregazione di codice ci consente di spostare il tutto in altri modelli di hosting se dovesse essere necessario.

## Deploy
E' infine possibile deployare quindi la Function App in Azure a partire dalla cartella `deploy` con i seguenti passi:
1) Effettuare il login con la Azure CLI:
    ``` pwsh 
    az login
    az account set -s <subscrpiontID>
    ```
2) E' necessario modificare il file `container-app.yaml` e modificare tutti i tutti i valori dei `<PLACEHOLDER>` con quelli delle risorse di destinazione
3) E' possibile quindi avviare la build dell'immagine del container:
    - E'necessario modificare il file `01_BuildImage.ps1`e modificare le seguenti variabili:
        ``` pwsh 
        $AcrName = "nome dell' azure container registry"
        $ImageName = "nome da dare all'immagine"
        $Tag = "tag da dare all'immagine"
        ```
       Opzionalment è possibile anche modificare:
        ``` pwsh 
        $BuiltUsingAcrTask = $false
        ```
       Se non si vuole usare Docker in locale per il build dell'immagine ma è necessario che l'Azure Container registry abbia un Task di build pre-configurato (necessario per le risorse con accesso pubblico disabilitato).
    - Eseguire lo script `01_BuildImage.ps1` ed attendere la pubblicazione dell'immagine
4) E' possibile quindi avviare il deploy della Function App in Azure Container App:
    - E' necessario modificare il file `02_DeployToAca.ps1` e modificare le seguenti variabili:
        ``` pwsh 
        $ContainerAppName = "il nome da dare alla container app"
        $ResourceGroup = "il resource group preesistente dove posizionare la container app" 
        ```
    - Eseguire lo script `02_DeployToAca.ps1` ed attendere la pubblicazione della Function App
5) Osservare dal portale Azure la sezione Log stream per analizzare il comportamento dellla funzione
       