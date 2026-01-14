# VS Code Configuration for Azure Functions

This directory contains VS Code workspace and project-specific configurations for developing Azure Functions in both C# and Java.

## Structure

```
aca-function-app/
├── .vscode/                          # Root workspace settings
│   ├── extensions.json               # Recommended extensions for the workspace
│   └── settings.json                 # Workspace-level settings
├── C#/functionapps/FunctionAppFileProcessor/.vscode/
│   ├── extensions.json               # C#-specific extensions
│   ├── settings.json                 # C# project settings
│   ├── tasks.json                    # Build and deploy tasks
│   └── launch.json                   # Debug configurations
└── Java/functionapps/FunctionAppFileProcessor/.vscode/
    ├── extensions.json               # Java-specific extensions
    ├── settings.json                 # Java project settings
    ├── tasks.json                    # Maven build tasks
    └── launch.json                   # Debug configurations
```

## Recommended Extensions

### Common Extensions (Workspace Level)
- **Azure Functions** - Core extension for Azure Functions development
- **Azure Account** - Azure authentication and subscription management
- **Azure Storage** - Manage Azure Storage accounts
- **Azure Event Grid** - Work with Event Grid subscriptions
- **Azure Container Apps** - Deploy and manage Container Apps
- **Azure Developer CLI (azd)** - Streamlined Azure deployment
- **Bicep** - Infrastructure as Code for Azure
- **Docker** - Container management
- **REST Client** - Test HTTP requests directly in VS Code

### C# Project Extensions
- **C# Dev Kit** - Comprehensive C# development tools
- **C#** - Language support for C#
- **.NET Runtime** - .NET runtime installer

### Java Project Extensions
- **Extension Pack for Java** - Complete Java development suite
- **Java Debug** - Debugging support for Java
- **Maven for Java** - Maven project management
- **Language Support for Java** - Java language server

## Settings

### C# Project Settings
- **Format on save**: Enabled for C# files
- **Auto organize imports**: Enabled
- **Default solution**: FunctionAppFileProcessor.sln
- **Deploy subpath**: bin/output
- **Pre-deploy task**: publish

### Java Project Settings
- **Build tool**: Maven
- **Format on save**: Enabled for Java files
- **Auto organize imports**: Enabled
- **Deploy subpath**: target/azure-functions/java-func-1766136297119
- **Pre-deploy task**: package (functions)
- **Java configuration**: Interactive build configuration updates

## Tasks

### C# Tasks
- **clean** - Clean build artifacts
- **build** - Build the project (default)
- **clean release** - Clean release build
- **publish** - Publish release build
- **func: host start** - Start Azure Functions host

### Java Tasks
- **package (functions)** - Run `mvn clean package` (default)
- **func: host start** - Start Azure Functions host with Java runtime

## Debug Configurations

### C# Debugging
- **Attach to .NET Functions** - Attach to running Functions host
- **.NET Core Launch (console)** - Launch Functions with debugger

### Java Debugging
- **Attach to Java Functions** - Attach to Java Functions on port 5005
  - Automatically starts Functions host before attaching

## Usage Tips

### Starting Development

1. **Install recommended extensions** - VS Code will prompt you when opening the workspace
2. **Configure local.settings.json** - Copy from local.settings.example.json and fill in Azure resource values
3. **Login to Azure** - Use Azure Account extension or run `az login` in terminal

### C# Development
1. Open the C# function app folder or the .sln file
2. Press `F5` or select "Attach to .NET Functions" to start debugging
3. Use `Ctrl+Shift+B` to build the project

### Java Development
1. Open the Java function app folder
2. Press `F5` or select "Attach to Java Functions" to start debugging
3. Maven will automatically compile and package the project
4. Use `Ctrl+Shift+B` to run the Maven package task

### File Exclusions
The following directories are excluded from file explorer and search:
- `bin/`, `obj/` (C# build output)
- `target/` (Java build output)
- `.git/`, `.DS_Store` (version control and OS files)
- `.classpath`, `.project`, `.settings/` (Java IDE files)

### Working with Azure Resources
- Use the Azure extension sidebar to browse and manage resources
- Connection strings and credentials use Azure CLI authentication in local development
- Managed Identity is used when deployed to Azure

## Troubleshooting

### C# Issues
- **Build errors**: Ensure .NET SDK 8 is installed (`dotnet --version`)
- **Functions not starting**: Check local.settings.json configuration
- **Extension errors**: Restart VS Code after installing extensions

### Java Issues
- **Maven errors**: Ensure Maven is installed (`mvn --version`)
- **Java version**: Requires Java 21 (`java --version`)
- **Debug port conflict**: Ensure port 5005 is not in use
- **Functions not starting**: Verify the deploySubpath matches the pom.xml configuration

### Common Issues
- **Azure authentication**: Run `az login` and select correct subscription
- **Missing extensions**: Check Output panel for extension installation errors
- **Azurite**: Start Azurite if using local storage emulation

## Additional Resources

- [Azure Functions VS Code Extension](https://marketplace.visualstudio.com/items?itemName=ms-azuretools.vscode-azurefunctions)
- [Azure Functions Documentation](https://learn.microsoft.com/azure/azure-functions/)
- [C# Dev Kit](https://marketplace.visualstudio.com/items?itemName=ms-dotnettools.csdevkit)
- [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack)
