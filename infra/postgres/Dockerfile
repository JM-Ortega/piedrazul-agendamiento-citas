FROM postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea

COPY init/01-init-databases.sh /docker-entrypoint-initdb.d/01-init-databases.sh
RUN chmod +x /docker-entrypoint-initdb.d/01-init-databases.sh
