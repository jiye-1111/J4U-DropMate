# Live diagnostic; credentials and raw responses are never printed or saved.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$settingsPath = Join-Path $PSScriptRoot '../app/src/main/assets/api.properties'
$apiKey = $null
foreach ($line in [System.IO.File]::ReadAllLines($settingsPath, [System.Text.Encoding]::UTF8)) {
    if ($line -match '^\s*kakao\.restApiKey\s*=\s*(.*?)\s*$') { $apiKey = $Matches[1] }
}
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'Kakao key is missing.' }
$queries = @((-join [char[]]@(49888,44396,44053)), (-join [char[]]@(49888,44396,44053,48320,50672,44032,50500,54028,53944)), (-join [char[]]@(49888,44396,44053,48320,50672,44032)), (-join [char[]]@(44288,50501,46041,48512)))
foreach ($query in $queries) {
    $uri = 'https://dapi.kakao.com/v2/local/search/keyword.json?query=' +
        [Uri]::EscapeDataString($query) + '&size=15&page=1&sort=accuracy'
    try {
        $response = Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = ('KakaoAK ' + $apiKey) } -TimeoutSec 15
        [pscustomobject]@{
            query = $query
            totalCount = $response.meta.total_count
            returnedCount = @($response.documents).Count
            places = @($response.documents | Select-Object -First 5 | ForEach-Object {
                [pscustomobject]@{ name = $_.place_name; roadAddress = $_.road_address_name; address = $_.address_name; id = $_.id }
            })
        } | ConvertTo-Json -Depth 4 -Compress
    } catch {
        $statusCode = $null
        if ($null -ne $_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
        [pscustomobject]@{ query = $query; error = 'Request failed'; statusCode = $statusCode } | ConvertTo-Json -Compress
    }
}
$apiKey = $null
$response = $null
