package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

/** Etapa de una conexión SMTP en la que ocurre una respuesta o un fallo. */
enum SmtpStage {
    /** Conexión, saludo, EHLO y autenticación: ningún comando de la transacción ha salido. */
    SESSION,
    TLS,
    MAIL,
    RCPT,
    /** Comando DATA enviado, a la espera del 354. */
    DATA,
    /** Cuerpo del mensaje saliendo hacia el servidor. */
    BODY,
    /** Secuencia final enviada, a la espera de la respuesta que decide la aceptación. */
    FINAL_REPLY;

    /** Etapas en las que el fallo precede a cualquier comando de la transacción. */
    boolean precedesTransaction() {
        return this == SESSION || this == TLS;
    }
}
