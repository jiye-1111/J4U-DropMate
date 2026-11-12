$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$apiKey = $null
foreach ($line in [IO.File]::ReadAllLines((Join-Path $PSScriptRoot '../app/src/main/assets/api.properties'), [Text.Encoding]::UTF8)) {
 if ($line -match '^\s*kakao\.restApiKey\s*=\s*(.*?)\s*$') { $apiKey = $Matches[1] }
}
$queries = @((-join [char[]]@(49888,44396)),(-join [char[]]@(49888,44396,32,44053)),(-join [char[]]@(49888,44396,44053,48320)))
foreach ($query in $queries) {
 try {
  $uri = 'https://dapi.kakao.com/v2/local/search/keyword.json?query=' + [Uri]::EscapeDataString($query) + '&size=15&page=1&sort=accuracy'
  $r = Invoke-RestMethod -Uri $uri -Headers @{Authorization=('KakaoAK '+$apiKey)} -TimeoutSec 15
  [pscustomobject]@{ query=$query; count=$r.meta.total_count; names=@($r.documents | ForEach-Object { $_.place_name }) } | ConvertTo-Json -Compress
 } catch { Write-Output 'Request failed (details omitted)' }
}
$apiKey=$null
$r=$null
