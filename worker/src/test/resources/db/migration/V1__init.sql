create table if not exists sources (
    id uuid primary key,
    name text unique not null,
    hmac_secret text not null,
    active boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists destinations (
    id uuid primary key,
    name text not null,
    target_url text not null,
    active boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists destination_rules (
    id uuid primary key,
    destination_id uuid not null references destinations(id) on delete cascade,
    source_name text not null,
    event_type text not null,
    unique(destination_id, source_name, event_type)
);

create table if not exists events (
    id uuid primary key,
    source_name text not null,
    event_type text not null,
    idempotency_key text not null,
    payload_json jsonb not null,
    received_at timestamptz not null default now(),
    unique(source_name, idempotency_key)
);

do $$ begin
    create type delivery_status as enum ('PENDING','DELIVERED','RETRYING','DEAD');
    exception when duplicate_object then null;
end $$;

create table if not exists deliveries (
    id uuid primary key,
    event_id uuid not null references events(id) on delete cascade,
    destination_id uuid not null references destinations(id),
    status delivery_status not null,
    attempts int not null default 0,
    max_attempts int not null default 5,
    last_error text null,
    last_attempt_at timestamptz null,
    delivered_at timestamptz null,
    created_at timestamptz not null default now(),
    unique(event_id, destination_id)
);

create index if not exists idx_deliveries_status on deliveries(status);
create index if not exists idx_events_received_at on events(received_at);
