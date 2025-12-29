$ProjectToBuild = $PSScriptRoot + "\..\functionapps\FunctionAppFileProcessor"
$AcrName = "<AZURE_CONTAINER_REGISTRY_NAME>"
$BuildTaskName = "buildcontainer"
$ImageName = "<IMAGE_NAME>"
$Tag = "<IMAGE_TAG>"
$BuiltUsingAcrTask = $false

if ($BuiltUsingAcrTask -eq $true) {
    Write-Host "Building image using ACR Task..."
    # Importante: Visto che è usato un Task ACR per la build è necessario rimuovere il Dockerfile dal file .dockerignore
    az acr task run -r $AcrName -n $BuildTaskName --set image="${ImageName}:${Tag}" --set dockerfile=$(Split-Path $ProjectToBuild -Leaf)/Dockerfile -c $ProjectToBuild/..
}
else {
    Write-Host "Building image using local Docker..."
    docker build -t "${AcrName}.azurecr.io/${ImageName}:${Tag}" -f "$ProjectToBuild\Dockerfile" $ProjectToBuild/..
    az acr login --name $AcrName
    docker push "${AcrName}.azurecr.io/${ImageName}:${Tag}"
}


