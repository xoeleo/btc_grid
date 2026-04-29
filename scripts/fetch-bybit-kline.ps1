param(
    [string]$Symbol = "BTCUSDT",
    [string]$Category = "linear",
    [int]$IntervalMinutes = 240,
    [int]$Days = 180,
    [string]$OutputFile = "runtime/btcusdt-4h-180d.csv"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$intervalMs = [int64]$IntervalMinutes * 60 * 1000
$endTime = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$startTime = $endTime - ([int64]$Days * 24 * 60 * 60 * 1000)
$cursorEnd = $endTime
$rows = New-Object System.Collections.Generic.List[object]

while ($cursorEnd -gt $startTime) {
    $url = "https://api.bybit.com/v5/market/kline?category=$Category&symbol=$Symbol&interval=$IntervalMinutes&limit=1000&end=$cursorEnd"
    $response = Invoke-RestMethod -Uri $url -Method Get
    if ($response.retCode -ne 0) {
        throw "Bybit API error: $($response.retCode) $($response.retMsg)"
    }

    $batch = @($response.result.list)
    if ($batch.Count -eq 0) {
        break
    }

    foreach ($entry in $batch) {
        $timestamp = [int64]$entry[0]
        if ($timestamp -lt $startTime -or $timestamp -gt $endTime) {
            continue
        }
        $rows.Add([PSCustomObject]@{
            timestamp = [DateTimeOffset]::FromUnixTimeMilliseconds($timestamp).UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ssZ")
            open      = $entry[1]
            high      = $entry[2]
            low       = $entry[3]
            close     = $entry[4]
            volume    = $entry[5]
        })
    }

    $oldestTimestamp = [int64]$batch[-1][0]
    $nextCursorEnd = $oldestTimestamp - $intervalMs
    if ($nextCursorEnd -ge $cursorEnd) {
        break
    }
    $cursorEnd = $nextCursorEnd
}

$sorted = $rows | Sort-Object timestamp -Unique
$outputPath = Join-Path (Get-Location) $OutputFile
$outputDir = Split-Path -Parent $outputPath
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

$sorted | Export-Csv -Path $outputPath -NoTypeInformation
Write-Host "rows=$($sorted.Count)"
Write-Host "output=$outputPath"
