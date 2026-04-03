create table if not exists messages (
    internal_id bigserial primary key,
    message_id varchar(128) not null unique,
    payload text not null,
    created_at timestamptz not null
);

