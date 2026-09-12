#!/bin/bash
# Creates one Postgres database per microservice on first container startup.
# The default POSTGRES_DB (postgres) is left alone; each service gets its own
# isolated database on the same Postgres instance (database-per-service pattern,
# without needing 6 separate Postgres containers on a laptop).
set -e

for DB in customer_db product_db inventory_db order_db payment_db shipping_db; do
  echo "Creating database: $DB"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT 'CREATE DATABASE $DB' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$DB')\gexec
EOSQL
done
