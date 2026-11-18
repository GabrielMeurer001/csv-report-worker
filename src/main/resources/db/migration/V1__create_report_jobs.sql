CREATE TABLE report_jobs (
    id uuid PRIMARY KEY,
    status text NOT NULL,
    csv_data bytea NOT NULL CHECK (octet_length(csv_data) <= 1048576),
    result jsonb,
    error jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (status IN ('pending', 'processing') AND result IS NULL AND error IS NULL)
        OR (status = 'completed' AND result IS NOT NULL AND error IS NULL)
        OR (status = 'failed' AND result IS NULL AND error IS NOT NULL)
    )
);
