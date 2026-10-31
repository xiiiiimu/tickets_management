param(
    [switch]$Compile,
    [switch]$Diagnostics,
    [ValidateSet('isolated','real')][string]$Mode='isolated',
    [ValidateRange(5,10)][int]$Duration=5,
    [string]$Steps='16:500,32:1000,64:2000,128:5000',
    [ValidateSet('reuse','close')][string]$Connections='reuse',
    [ValidateRange(0,100)][int]$ConsumerMs=0,
    [int]$Port=18088,
    [string]$Fixture=''
)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$javaBin='C:\Program Files\Java\jdk-21.0.11\bin'
$target=Join-Path $repo 'ticket-risk-platform/target'
$cpfile=Join-Path $target 'benchmark-classpath.txt'
$launch=Join-Path $target 'fast-ingress-benchmark-classes'
if($Steps -notmatch '^\d+:\d+(,\d+:\d+)*$'){throw 'Steps must be concurrency:rate pairs'}
if($Compile){
    if(!(Test-Path (Join-Path $target 'classes/com/ticketing/ticket/ReservationIngress.class'))){throw 'Compile the affected main module first; the quick runner does not run a full build automatically'}
    if(!(Test-Path $cpfile)){
        & mvn -f (Join-Path $repo 'ticket-risk-platform/pom.xml') dependency:build-classpath '-Dmdep.includeScope=test' ('-Dmdep.outputFile='+$cpfile)
        if($LASTEXITCODE -ne 0){throw 'Dependency classpath generation failed'}
    }
    New-Item -ItemType Directory -Force -Path $launch | Out-Null
    $classpath=$target+'/classes;'+(Get-Content -Raw $cpfile).Trim()
    $timer=[Diagnostics.Stopwatch]::StartNew()
    & (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -proc:none -source 8 -target 8 -cp $classpath -d $launch (Join-Path $repo 'ticket-risk-platform/src/test/java/benchmark/FastIngressApplication.java') (Join-Path $repo 'ticket-risk-platform/src/test/java/benchmark/FastIngressDiagnostics.java')
    if($LASTEXITCODE -ne 0){throw 'Test-only launcher compile failed'}
    Write-Output ('Test-only compile seconds: '+$timer.Elapsed.TotalSeconds.ToString('F2'))
}
$oldEncoding=$env:PYTHONIOENCODING
try {
    $env:PYTHONIOENCODING='utf-8'
    $arguments=@((Join-Path $PSScriptRoot 'fast_ingress_benchmark.py'),'--mode',$Mode,'--duration',[string]$Duration,'--steps',$Steps,'--connections',$Connections,'--consumer-ms',[string]$ConsumerMs,'--port',[string]$Port)
    if($Fixture){$arguments+=@('--fixture',$Fixture)}
    if($Diagnostics){$arguments+='--diagnostics'}
    & python @arguments
    if($LASTEXITCODE -ne 0){throw 'Short benchmark failed; inspect retained raw result directory'}
} finally {$env:PYTHONIOENCODING=$oldEncoding}
