param(
    [string]$CamundaHome = (Join-Path $PSScriptRoot '../../../camunda8-getting-started-bundle-8.9.0-windows-x86_64/c8run-8.9.0/camunda-zeebe-8.9.0'),
    [string]$RuntimeDirectory = (Join-Path $env:LOCALAPPDATA 'HPAS/acceptance-runtime')
)
$ErrorActionPreference = 'Stop'
$CamundaHome = (Resolve-Path -LiteralPath $CamundaHome).Path
if (Get-NetTCPConnection -State Listen -LocalPort 8180,19600,27500,27501,27502 -ErrorAction SilentlyContinue) {
    throw 'Acceptance ports are in use. Reuse the running acceptance runtime or stop its owner before restarting.'
}
New-Item -ItemType Directory -Force -Path $RuntimeDirectory | Out-Null
$runtimePath = $RuntimeDirectory.Replace('\', '/')
$homePath = $CamundaHome.Replace('\', '/')
$java = (Get-Command java.exe).Source
$arguments = @(
    '-Xmx768m', "`"-Dapp.home=$runtimePath`"", '-Dzeebe-clock-controlled=true',
    '-cp', "`"$homePath/driver-lib/*;$homePath/config;$homePath/lib/*`"",
    'io.camunda.application.StandaloneCamunda',
    '--server.address=127.0.0.1', '--server.port=8180',
    '--management.server.address=127.0.0.1', '--management.server.port=19600',
    '--zeebe.broker.network.host=127.0.0.1',
    '--zeebe.broker.gateway.network.host=127.0.0.1',
    '--zeebe.broker.gateway.network.port=27500',
    '--zeebe.broker.network.commandApi.port=27501',
    '--zeebe.broker.network.internalApi.port=27502',
    "`"--zeebe.broker.data.directory=$runtimePath/zeebe`"",
    '--camunda.data.secondary-storage.type=rdbms',
    "`"--camunda.data.secondary-storage.rdbms.url=jdbc:h2:file:$runtimePath/h2db`"",
    '--camunda.data.secondary-storage.rdbms.username=sa',
    '--camunda.security.authentication.unprotected-api=true',
    '--camunda.security.authorizations.enabled=false'
)
$previousClock = $env:ZEEBE_CLOCK_CONTROLLED
try {
    $env:ZEEBE_CLOCK_CONTROLLED = 'true'
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $RuntimeDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $RuntimeDirectory 'stdout.log') `
        -RedirectStandardError (Join-Path $RuntimeDirectory 'stderr.log')
} finally {
    $env:ZEEBE_CLOCK_CONTROLLED = $previousClock
}
$process.Id | Set-Content (Join-Path $RuntimeDirectory 'runtime.pid')
Write-Host "Acceptance runtime starting (PID $($process.Id)); REST 8180, gRPC 27500, monitoring 19600."
Write-Host "Logs: $RuntimeDirectory. Run Version2AcceptanceIT after startup completes."
