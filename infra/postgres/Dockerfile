FROM pgvector/pgvector:0.7.4-pg16 AS pgvector
FROM postgis/postgis:16-3.4
COPY --from=pgvector /usr/lib/postgresql/16/lib/vector.so /usr/lib/postgresql/16/lib/
COPY --from=pgvector /usr/share/postgresql/16/extension/vector* /usr/share/postgresql/16/extension/
