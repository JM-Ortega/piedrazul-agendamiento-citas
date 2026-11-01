# Autorizaciones de break-glass de escaneo

Un archivo `BG-AAAA-NNN.json` por autorización: una sola operación descrita exactamente, sin
Terraform Apply, máximo 72 h. Solo para una caída del escáner, registry o DB de
vulnerabilidades; no exime CRITICAL conocidos. Cada id se consume una sola vez: editarlo,
borrarlo y recrearlo o re-fusionarlo no lo renueva.

Es efectiva solo si un aprobador autorizado la aceptó: con su revisión `APPROVED` antes del
merge (`independent_review`), o abriendo y fusionando él mismo, con merge commit, un PR que
solo trae registros de `security/` (`self_merge`). Un agente puede redactarla; nunca la aprueba,
la fusiona ni dispara su uso. Formato y condiciones:
[política de vulnerabilidades](../../docs/operations/runbooks/politica-vulnerabilidades-imagenes.md#break-glass-de-escaneo).
