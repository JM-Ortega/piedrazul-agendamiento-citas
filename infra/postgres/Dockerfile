FROM postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea

COPY init/01-init-databases.sh /docker-entrypoint-initdb.d/01-init-databases.sh

# Arranque sin root: el entrypoint oficial solo usa gosu para bajar de root a postgres, y
# corriendo ya como postgres (uid 70) no lo invoca. gosu se quita porque su Go stdlib
# embebido es el origen de los CRITICAL/HIGH del escaneo de la base.
# Supuesto: PGDATA pertenece a postgres. El volumen nombrado de Compose lo cumple (nuevo:
# hereda el dueño del directorio de la imagen; inicializado antes con root: el entrypoint
# ya lo había pasado a postgres). Sin root, el entrypoint no corrige dueños: un PGDATA
# restaurado o montado con otro dueño debe quedar como 70:70 antes de arrancar.
RUN chmod +x /docker-entrypoint-initdb.d/01-init-databases.sh \
 && rm -f /usr/local/bin/gosu

USER postgres
