$ErrorActionPreference = 'Stop'
$runtimeDir = 'D:\桌面\26.9-26.12\demo\advanced_info_sys\camunda8-getting-started-bundle-8.9.0-windows-x86_64\c8run-8.9.0'
$runtimeProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -match 'org.springframework.boot.loader.launch.PropertiesLauncher' -and
    $_.CommandLine.Replace('/', '\').Contains($runtimeDir)
})
if ($runtimeProcesses.Count -gt 1) { throw 'Multiple Connector Runtime processes found; check before restarting.' }
foreach ($runtimeProcess in $runtimeProcesses) {
    Stop-Process -Id $runtimeProcess.ProcessId
    Wait-Process -Id $runtimeProcess.ProcessId -Timeout 20 -ErrorAction SilentlyContinue
}
# Camunda 8.9 defaults to SECRET_ (singular).
$env:SECRET_SP05_AVAILABILITY_URL = 'http://localhost:4510/availability'
$env:CAMUNDA_CLIENT_GRPCADDRESS = 'http://127.0.0.1:26500'
$env:CAMUNDA_CLIENT_RESTADDRESS = 'http://127.0.0.1:8080'
$runtimeArgs = '-classpath "' + $runtimeDir + '\*;' + $runtimeDir + '\custom_connectors\*" org.springframework.boot.loader.launch.PropertiesLauncher --spring.config.additional-location="' + $runtimeDir + '\connectors-application.properties"'
$started = Start-Process -FilePath 'C:\Program Files\Java\jdk-22\bin\java.exe' -ArgumentList $runtimeArgs -WorkingDirectory $runtimeDir -WindowStyle Hidden -RedirectStandardOutput ($runtimeDir + '\log\sp05-connectors.out.log') -RedirectStandardError ($runtimeDir + '\log\sp05-connectors.err.log') -PassThru
Set-Content -LiteralPath ($runtimeDir + '\connectors.process') -Value $started.Id
Write-Host "Connector Runtime restarted: $($started.Id)"
Write-Host 'SP05_AVAILABILITY_URL=http://localhost:4510/availability'
