FROM postgres:16-alpine

# Mete el script dentro de la imagen eliminando la dependencia a el host haciendolo mucho más portable
# Ahora la imagen es autocontenida
COPY infra/postgres/init/01-init-databases.sh /docker-entrypoint-initdb.d/01-init-databases.sh

RUN sed -i 's/\r$//' /docker-entrypoint-initdb.d/01-init-databases.sh \
    && chmod 755 /docker-entrypoint-initdb.d/01-init-databases.sh