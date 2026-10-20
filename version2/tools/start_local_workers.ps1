param([switch]$ReplaceExisting)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../java')).Path
$jar=Join-Path $project 'target/hpas-redesigned-workers-1.0.0.jar'
$logs=Join-Path $PSScriptRoot '../evidence/subprocess-layout-deploy-20261020'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the worker with mvn package first.' }
$existing=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -match 'io\.camunda\.demo\.hpas\.HpasWorkerApplication|hpas-redesigned-workers-1\.0\.0\.jar'
})
if ($existing.Count -gt 0 -and -not $ReplaceExisting) { throw 'Hospital workers already exist. Use -ReplaceExisting to restart them explicitly.' }
foreach ($proc in $existing) {
    $v2=$proc.CommandLine.Contains($project) -or $proc.CommandLine.Contains($project.Replace('\','/'))
    if (-not $v2 -and $proc.CommandLine -match '@([^ ]+\.argfile)') {
        $argfile=$Matches[1]
        if (Test-Path -LiteralPath $argfile) {
            $argsText=Get-Content -LiteralPath $argfile -Raw
            $normalArgs=$argsText.Replace('\\','\').Replace('/','\')
            $v2=$normalArgs.Contains($project.Replace('/','\'))
        }
    }
    if (-not $v2) { throw "Hospital Worker PID $($proc.ProcessId) is not verified as this Version 2 project; stop before replacing it." }
}
# Verify all owners before stopping any worker; cluster and connectors are not matched.
foreach ($proc in $existing) { Stop-Process -Id $proc.ProcessId; Wait-Process -Id $proc.ProcessId -Timeout 15 -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$java=(Get-Command java.exe).Source
$db=(Join-Path $project 'data/hpas-redesign').Replace('\','/')
$arguments=@('-Xmx384m','-jar',"`"$jar`"",'--camunda.client.grpc-address=http://127.0.0.1:26500',
    '--camunda.client.rest-address=http://127.0.0.1:8080',"`"--spring.datasource.url=jdbc:h2:file:$db;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1`"",
    '--hpas.simulation.external-responses=true')
$worker=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logs 'workers.out.log') -RedirectStandardError (Join-Path $logs 'workers.err.log')
$record=[ordered]@{pid=$worker.Id;replacedPids=@($existing.ProcessId);jar=$jar;jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;rest='http://127.0.0.1:8080';grpc='http://127.0.0.1:26500';database=$db;startedAt=(Get-Date).ToString('o')}
$record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $logs 'worker-start.json') -Encoding utf8
$record | ConvertTo-Json -Depth 5
