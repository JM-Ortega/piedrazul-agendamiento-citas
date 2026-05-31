FROM postgres:16-alpine

COPY init/01-init-databases.sh /docker-entrypoint-initdb.d/01-init-databases.sh
RUN chmod +x /docker-entrypoint-initdb.d/01-init-databases.sh
