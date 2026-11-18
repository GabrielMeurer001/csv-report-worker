$ErrorActionPreference = 'Stop'
$api = 'http://127.0.0.1:8080'
$broker = 'http://127.0.0.1:15672/api'
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('reports:reports-local'))

function Assert-Demo($condition, $message) {
    if (!$condition) { throw $message }
}

function Invoke-Sql($sql) {
    $output = $sql | docker compose exec -T postgres psql -X -U reports -d reports -v ON_ERROR_STOP=1 -qAt
    if ($LASTEXITCODE -ne 0) { throw 'psql failed' }
    return $output
}

function Send-Csv($path, $expected) {
    $response = curl.exe --silent --show-error --max-time 20 --write-out '\n%{http_code}' `
        --form "file=@$path" "$api/reports"
    Assert-Demo ($LASTEXITCODE -eq 0) 'Upload request failed'
    Assert-Demo ($response[-1] -eq $expected) "Expected HTTP $expected. Received: $response"
    return ($response[0] | ConvertFrom-Json)
}

function Wait-Report($id) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $report = Invoke-RestMethod "$api/reports/$id" -TimeoutSec 5
        if ($report.status -in @('completed', 'failed')) { return $report }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Report $id did not reach a terminal state"
}

function Invoke-Broker($path, $body) {
    $headers = @{ Authorization = $auth }
    $json = $body | ConvertTo-Json -Compress
    return Invoke-RestMethod "$broker/$path" -Method Post -Headers $headers -ContentType 'application/json' `
        -Body $json -TimeoutSec 5
}

function Publish-Job($id) {
    $published = Invoke-Broker 'exchanges/%2F/reports/publish' @{
        properties = @{ content_type = 'text/plain'; content_encoding = 'UTF-8'; delivery_mode = 2 }
        routing_key = 'reports.jobs'
        payload = "$id"
        payload_encoding = 'string'
    }
    Assert-Demo $published.routed "Job $id was not routed"
}

function Assert-Result($report) {
    Assert-Demo ($report.status -eq 'completed' -and $report.result.saleCount -eq 3) 'Expected three sales'
    Assert-Demo ($report.result.total -ceq '200.00') 'Incorrect report total'
    Assert-Demo ($report.result.totalsByCategory.livros -ceq '80.00') 'Incorrect livros total'
    Assert-Demo ($report.result.totalsByCategory.jogos -ceq '120.00') 'Incorrect jogos total'
}

Push-Location (Join-Path $PSScriptRoot '..')
try {
    $valid = Send-Csv 'examples/sales.csv' 202
    Assert-Result (Wait-Report $valid.id)
    $missing = curl.exe -sS -o NUL -w '%{http_code}' "$api/reports/$([Guid]::NewGuid())"
    Assert-Demo ($missing -eq 404) 'Expected HTTP 404 for unknown report'

    $invalid = Wait-Report (Send-Csv 'examples/invalid-sales.csv' 202).id
    Assert-Demo ($invalid.status -eq 'failed' -and $invalid.error.code -eq 'INVALID_CSV' -and $invalid.error.line -eq 3) `
        'Invalid amount must fail on line 3'

    $largeFile = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllBytes($largeFile, [byte[]]::new(1MB + 1))
        Assert-Demo ((Send-Csv $largeFile 413).code -eq 'FILE_TOO_LARGE') 'Wrong upload size error'
    } finally {
        Remove-Item -LiteralPath $largeFile
    }
    Write-Host 'PASS: valid CSV, invalid CSV, 404 and 413'

    $original = Invoke-Sql "SELECT updated_at, result FROM report_jobs WHERE id = '$($valid.id)';"
    Publish-Job $valid.id
    Publish-Job $valid.id
    # A new job on the same queue proves that the single consumer passed both duplicate messages.
    Assert-Result (Wait-Report (Send-Csv 'examples/sales.csv' 202).id)
    $current = Invoke-Sql "SELECT updated_at, result FROM report_jobs WHERE id = '$($valid.id)';"
    Assert-Demo ($original -ceq $current) 'Duplicate delivery modified the terminal result'
    Write-Host 'PASS: duplicate deliveries leave the committed report untouched'

    $recovering = [Guid]::NewGuid()
    $exhausted = [Guid]::NewGuid()
    $suffix = $recovering.ToString('N')
    $trigger = "verify_trigger_$suffix"
    $function = "verify_failure_$suffix"
    $sequence = "verify_attempt_$suffix"
    $encodedCsv = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'examples/sales.csv').Path))
    try {
        Invoke-Sql @"
BEGIN;
CREATE SEQUENCE $sequence;
CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS `$body`$
BEGIN
    IF NEW.id = '$recovering' AND nextval('$sequence') = 1 THEN
        RAISE EXCEPTION 'temporary verification failure' USING ERRCODE = '40001';
    END IF;
    IF NEW.id = '$exhausted' THEN
        PERFORM nextval('$sequence');
        RAISE EXCEPTION 'persistent verification failure' USING ERRCODE = '40001';
    END IF;
    RETURN NEW;
END;
`$body`$;
CREATE TRIGGER $trigger BEFORE UPDATE ON report_jobs
FOR EACH ROW WHEN (NEW.status = 'completed' AND NEW.id IN ('$recovering', '$exhausted'))
EXECUTE FUNCTION $function();
INSERT INTO report_jobs (id, status, csv_data) VALUES
    ('$recovering', 'processing', decode('$encodedCsv', 'base64')),
    ('$exhausted', 'pending', decode('$encodedCsv', 'base64'));
COMMIT;
"@
        Publish-Job $recovering
        Assert-Result (Wait-Report $recovering)
        Assert-Demo ((Invoke-Sql "SELECT last_value FROM $sequence;") -eq 2) 'Expected recovery on the second attempt'

        Publish-Job $exhausted
        $failure = Wait-Report $exhausted
        Assert-Demo ($failure.status -eq 'failed' -and $failure.error.code -eq 'PROCESSING_FAILED') 'Expected persistent failure'
        Assert-Demo ((Invoke-Sql "SELECT last_value FROM $sequence;") -eq 5) 'Expected exactly three failed attempts'

        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $dead = Invoke-Broker 'queues/%2F/reports.dlq/get' @{
                count = 100
                ackmode = 'ack_requeue_true'
                encoding = 'auto'
                truncate = 1000
            }
            if ($dead.payload -contains "$exhausted") { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        Assert-Demo ($dead.payload -contains "$exhausted") 'Expected the failed UUID in reports.dlq'
        Assert-Demo ($dead.payload -notcontains $invalid.id) 'Invalid CSV must not be dead-lettered'
        Write-Host 'PASS: resume processing, retry and recover, exhaust three attempts and route to DLQ'
    } finally {
        Invoke-Sql "DROP TRIGGER IF EXISTS $trigger ON report_jobs; DROP FUNCTION IF EXISTS $function(); DROP SEQUENCE IF EXISTS $sequence;"
    }

    try {
        docker compose stop rabbitmq
        Assert-Demo ($LASTEXITCODE -eq 0) 'Could not stop RabbitMQ'
        $unavailable = Send-Csv 'examples/sales.csv' 503
        $failed = Wait-Report $unavailable.id
        Assert-Demo ($failed.status -eq 'failed' -and $failed.error.code -eq 'QUEUE_UNAVAILABLE') 'Broker outage must be recorded'
    } finally {
        docker compose up -d --wait rabbitmq
        Assert-Demo ($LASTEXITCODE -eq 0) 'Could not restore RabbitMQ'
    }
    Assert-Result (Wait-Report (Send-Csv 'examples/sales.csv' 202).id)
    Write-Host 'PASS: RabbitMQ outage returns 503 with a queryable job and new uploads recover'
} finally {
    Pop-Location
}
