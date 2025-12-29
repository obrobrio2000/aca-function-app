
$ContainerAppName = "<CONTAINER_APP_NAME>"
$ResourceGroup = "<RESOURCE_GROUP>"  # Specifica il tuo resource group
$YamlFile = $PSScriptRoot+"/container-app.yaml"

#az containerapp update --name $ContainerAppName --resource-group $ResourceGroup --yaml $YamlFile

az containerapp create --name $ContainerAppName --resource-group $ResourceGroup --kind functionapp --yaml $YamlFile