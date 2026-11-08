# Apartamentos Ochavada · Backend

API REST del sistema de gestión del apartahotel: PMS, motor de reservas, portal del huésped con
cerraduras TTLock, partes SES.HOSPEDAJES y distribución multicanal vía Beds24.

**Stack:** Java 21 · Spring Boot 4.1 · Spring Security 7 (JWT HS256, sin estado) · Spring Data JPA /
Hibernate 7 · Flyway · PostgreSQL 16 · Spring Cloud OpenFeign 2025.1.

## Estructura de paquetes

```
com.ochavada.backend
├── auth/            Login, refresh con rotación, logout, /me
├── access/          Llave virtual: cerraduras, autocheck-in, aperturas y su auditoría
│   └── ttlock/      Cliente OpenFeign de la TTLock Open API v3 y gestión del token OAuth
├── audit/           Registro de auditoría (append-only)
├── availability/    Inventario (SQL nativo), presupuestos de estancia y buscador público
├── catalog/         Tipologías, fotos, servicios, unidades y bloqueos
├── channel/         Channel manager: marcas de sincronización
│   └── beds24/      API V2 de Beds24 (OpenFeign): inventario, reservas propias, importación de OTAs, webhook
├── checkin/         Check-in online: registro de viajeros y reglas del parte (RD 933/2021)
├── checkout/        Checkout en 2 pasos, retenciones y su caducidad
├── common/          Entidad versionada, Problem Details RFC 7807, slugs, límite por IP, paginación
├── config/          Reloj, CORS, datos del establecimiento (zona horaria, moneda, horarios)
├── guest/           Fichas de huésped (RD 933/2021) e historial
├── guestportal/     Enlaces mágicos y portal del huésped
├── housekeeping/    Tareas de limpieza y estado de las unidades
├── integration/     Outbox transaccional y webhooks entrantes
├── invoicing/       Facturas F1/F2/rectificativas, numeración y huella VERI*FACTU
├── notification/    Emails transaccionales
├── payment/         Cobros, pasarela Stripe y webhooks
├── pms/             Rack de disponibilidad y panel de recepción
├── rates/           Planes de tarifa, temporadas, ajustes diarios y calendario
│   └── pricing/     Motor de precios y restricciones (puro, sin Spring)
├── reservation/     Reserva y su máquina de estados
│   └── backoffice/  Alta manual, edición, cambios de unidad, check-in/out, cobros y reembolsos
├── security/        SecurityConfig, roles, JWT (emisión/validación), entry points 401/403
├── ses/             Comunicaciones a SES.HOSPEDAJES (RH, PV, anulaciones) y seguimiento de lotes
│   └── client/      SOAP 1.1, XML (StAX), ZIP+Base64 y parser endurecido de respuestas
└── user/            Usuarios del backoffice y alta del administrador inicial
```

## Requisitos

- JDK 21
- PostgreSQL 16 (en local o vía Docker Compose en la fase de infraestructura)
- Maven no es necesario: se usa el wrapper `./mvnw`

## Ejecutar en local

```bash
# Base de datos local
createdb ochavada

# Arranque con el perfil de desarrollo
SPRING_PROFILES_ACTIVE=dev \
APP_BOOTSTRAP_ADMIN_EMAIL=admin@apartamentosochavada.com \
APP_BOOTSTRAP_ADMIN_PASSWORD='una-contraseña-larga' \
./mvnw spring-boot:run
```

Flyway crea el esquema al arrancar. El administrador inicial solo se crea si no existe ningún ADMIN.

## Arranque local sin Docker

Para desarrollar el frontend contra la API real sin instalar PostgreSQL, `LocalDevServer` (en los fuentes de
test) levanta el backend con el perfil `dev` sobre el PostgreSQL 16 embebido de los tests. Los datos se pierden
al parar. Crea el administrador `admin@ochavada.local` (contraseña `Admin-Local-2026!` o `DEV_ADMIN_PASSWORD`):

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.ochavada.backend.LocalDevServer
```

## Despliegue con Docker

La imagen se construye con el `Dockerfile` de este repositorio y se despliega junto al resto de servicios
(PostgreSQL, frontend/backend y Caddy) con el Docker Compose de `../ApartamentosOchavadaInfra`; ver su README.

## Tests

```bash
./mvnw test
```

Los tests de integración arrancan un PostgreSQL 16 real embebido (zonky): no hace falta Docker.

## Autenticación

| Endpoint | Descripción |
|---|---|
| `POST /api/v1/auth/login` | `{email, password}` → access token (15 min) en el cuerpo + refresh token en cookie `HttpOnly` |
| `POST /api/v1/auth/refresh` | Rota el refresh token de la cookie y devuelve un nuevo access token |
| `POST /api/v1/auth/logout` | Revoca la sesión y elimina la cookie |
| `GET /api/v1/auth/me` | Usuario autenticado (`Authorization: Bearer …`) |
| `PUT /api/v1/auth/password` | Cambio de la propia contraseña; cierra todas las sesiones y abre una nueva |

### Usuarios del backoffice (ADMIN)

| Endpoint | Descripción |
|---|---|
| `GET /api/v1/admin/users` | Listado con estado (activo, bloqueado, pendiente de primer acceso) |
| `POST /api/v1/admin/users` | Alta: genera una contraseña temporal que se devuelve una sola vez |
| `PUT /api/v1/admin/users/{id}` | Nombre, teléfono, rol y activo/desactivado (con `version`) |
| `POST /api/v1/admin/users/{id}/reset-password` | Nueva contraseña temporal |
| `POST /api/v1/admin/users/{id}/unlock` · `/revoke-sessions` | Quitar el bloqueo por intentos fallidos · cerrar todas sus sesiones |

Con contraseña temporal, el token solo permite `GET /auth/me` y `PUT /auth/password`. Desactivar, cambiar el rol o
resetear la contraseña invalida al momento los access tokens vigentes (cada token del personal se contrasta con la
base de datos, con una caché de 30 s por instancia). Contraseñas: 12–64 caracteres, sin el email; los usuarios no se
borran (se desactivan) y siempre queda un administrador activo.

Las rutas se protegen por prefijo: `/api/v1/public/**` y `/api/v1/webhooks/**` son públicas,
`/api/v1/backoffice/**` exige un rol de personal, `/api/v1/admin/**` exige ADMIN y
`/api/v1/guest/**` exige un token de huésped. Cualquier otra ruta se deniega.

Todos los errores siguen RFC 7807 (`application/problem+json`) con los miembros adicionales `code` y `timestamp`.

## Catálogo, tarifas y disponibilidad

| Endpoint | Rol | Descripción |
|---|---|---|
| `GET /api/v1/public/room-types` · `/{slug}` | público | Catálogo y ficha (caché 60 s) |
| `GET /api/v1/public/availability?checkIn&checkOut&adults&children` | público | Tipologías libres con tarifas presupuestadas |
| `/api/v1/admin/room-types` (+ `/{id}/photos`, `/{id}/amenities`) | ADMIN | CRUD de tipologías, fotos y servicios |
| `/api/v1/admin/amenities` | ADMIN | CRUD de servicios |
| `/api/v1/admin/rooms` · `GET /api/v1/backoffice/rooms` | ADMIN · personal | Unidades |
| `/api/v1/backoffice/room-blocks` | RECEPTIONIST | Bloqueos (no pueden pisar reservas) |
| `/api/v1/admin/rate-plans` (+ `/{id}/seasons`, `PUT /{id}/daily-rates`) | ADMIN | Planes, temporadas y edición masiva diaria |
| `GET /api/v1/backoffice/rate-plans/{id}/calendar?from&to` | RECEPTIONIST | Precio, restricciones y unidades libres por día |

**Precedencia de precios:** ajuste diario > temporada (precio de fin de semana viernes y sábado si se define) >
precio base del plan. Estancia mínima/máxima y cierre a llegadas se evalúan en el día de llegada; cierre a
salidas en el día de salida; stop-sell en cada noche.

**Convención de fechas:** estancias y bloqueos usan `[entrada, salida)` (el día de salida queda libre);
temporadas y ajustes diarios se expresan en noches con ambos extremos incluidos (`firstNight`, `lastNight`).

**Concurrencia:** las modificaciones (`PUT`) exigen el campo `version` leído; si otro usuario cambió el
registro entretanto se responde 409 `data.concurrent_modification`.

## Reserva online y pago (Stripe)

```
Web                         API                                   Stripe
 │ POST /public/checkout ───▶ recalcula precio, asigna unidad,
 │   (Idempotency-Key)        reserva PENDING_PAYMENT (15 min) ──▶ PaymentIntent
 │ ◀── clientSecret + checkoutToken
 │ stripe.confirmPayment() ──────────────────────────────────────▶ cobro
 │                            POST /webhooks/stripe ◀──────────── payment_intent.succeeded
 │                            reserva CONFIRMED + email (outbox)
 │ GET /public/checkout/{code} (X-Checkout-Token) ── hasta CONFIRMED
```

| Endpoint | Descripción |
|---|---|
| `POST /api/v1/public/checkout` | Paso 1: retención + PaymentIntent. Sin prepago (0 %) la reserva queda confirmada directamente |
| `GET /api/v1/public/checkout/{code}` | Estado del pago (cabecera `X-Checkout-Token`) |
| `POST /api/v1/public/checkout/{code}/cancel` | Abandono: libera la unidad si no hay cobro en curso |
| `POST /api/v1/webhooks/stripe` | Webhooks firmados (`Stripe-Signature`), idempotentes por id de evento |
| `POST /api/v1/guest/auth/exchange` | Canjea el enlace del email (`#acceso=…`) por un token de huésped |
| `GET /api/v1/guest/stay` | Resumen de la estancia para el portal del huésped |

Garantías:
- **Sin overbooking:** la restricción `EXCLUDE` de PostgreSQL resuelve carreras entre dos clientes por la misma unidad.
- **El precio lo calcula siempre el servidor**; el navegador nunca envía importes.
- **Retenciones caducadas** se liberan cada minuto, pero solo después de cancelar el PaymentIntent en Stripe:
  si el pago ya está cobrado o en proceso, la retención se amplía y el webhook confirmará la reserva.
- **Emails por outbox transaccional** con reintentos exponenciales; el enlace mágico se genera en la misma
  transacción del envío y solo se guarda su hash.
- **Límite por IP** en el checkout y en el canje de enlaces (`app.rate-limit`).

## PMS (backoffice)

| Endpoint | Rol | Descripción |
|---|---|---|
| `GET /api/v1/backoffice/rack?from&to` | RECEPTIONIST | Gantt por unidad: reservas, bloqueos y reservas sin unidad (máx. 62 días) |
| `GET /api/v1/backoffice/dashboard?date` | RECEPTIONIST | Llegadas, salidas, ocupación, ingresos, ADR, RevPAR, limpieza |
| `GET/POST /api/v1/backoffice/reservations` | RECEPTIONIST | Listado/búsqueda y alta manual (teléfono, mostrador, OTA) |
| `PATCH /reservations/{id}` · `PUT /{id}/stay` · `PUT /{id}/room` | RECEPTIONIST | Notas, cambio de fechas (recalcula precio), cambio de unidad |
| `POST /reservations/{id}/check-in` · `/check-out` · `/no-show` · `/cancel` | RECEPTIONIST | Recepción (`{"force":true}` salta avisos de limpieza o saldo) |
| `POST /reservations/{id}/payments` · `POST /payments/{id}/refund` | RECEPTIONIST · ADMIN | Cobros en el alojamiento y reembolsos (Stripe incluido) |
| `/api/v1/backoffice/housekeeping/tasks` | HOUSEKEEPING / RECEPTIONIST | Tablero, inicio, fin y verificación de tareas |
| `/api/v1/backoffice/guests` | RECEPTIONIST | Búsqueda, ficha (datos RD 933/2021) e historial |
| `POST /reservations/{id}/invoices` · `/invoices/{id}/rectify` | RECEPTIONIST · ADMIN | Factura F1/F2 y rectificativa por diferencias |
| `GET /api/v1/backoffice/invoices/{id}/document` | RECEPTIONIST | Factura imprimible (HTML) |

**Facturación:** precios con IVA incluido (10 %), numeración correlativa sin huecos por serie y año
(`A` completas, `T` simplificadas, `R` rectificativas), facturas inmutables en base de datos y huella
VERI*FACTU encadenada (verificada contra el ejemplo oficial de la AEAT). La remisión de los registros a la
AEAT no está implementada: con `VERIFACTU_ENABLED=true` quedan en estado `PENDING`.

## Check-in online y SES.HOSPEDAJES (RD 933/2021)

| Endpoint | Rol | Descripción |
|---|---|---|
| `GET/PUT /api/v1/guest/travelers` | huésped | Check-in online: datos de todos los viajeros |
| `GET/PUT /api/v1/backoffice/reservations/{id}/travelers` | RECEPTIONIST | Registro de viajeros en el mostrador |
| `GET /api/v1/backoffice/ses/reports` · `/{id}` | RECEPTIONIST | Comunicaciones abiertas, instantánea enviada y respuesta |
| `POST /api/v1/backoffice/ses/reports/{id}/submit` | RECEPTIONIST | Regenerar y reenviar tras corregir datos |
| `GET /api/v1/backoffice/reservations/{id}/ses-reports` | RECEPTIONIST | Historial de comunicaciones de una reserva |

Comunicaciones generadas automáticamente (plazo legal 24 h, visible en el panel):

| Hecho | Comunicación |
|---|---|
| Reserva confirmada (web, teléfono, mostrador) | Alta de reserva de hospedaje (**RH**) con el titular |
| Check-in (recepción o autocheck-in) | Alta de parte de viajeros (**PV**) con todos los viajeros |
| Cambio de fechas/ocupación o corrección de viajeros | Anulación (**B**) de la vigente + nueva alta |
| Cancelación | Anulación (**B**) de la reserva comunicada |

Implementado según la *Interfaz servicios externos – Servicio de Comunicación Hospedajes v3.1.2* del
Ministerio del Interior: SOAP 1.1 con autenticación básica, cabecera `codigoArrendador`/`aplicacion`/
`tipoOperacion`/`tipoComunicacion`, solicitud XML UTF-8 comprimida en ZIP y codificada en Base64, consulta
del estado de los lotes (operación C) hasta obtener el código de comunicación o el error de validación.
Los datos se validan antes de enviarlos (documento obligatorio a mayores de edad, número de soporte con
DNI/NIE y letra de control, segundo apellido con DNI, municipio INE para residentes en España, parentesco
de los menores, un contacto como mínimo). El check-in en recepción exige los viajeros completos (o forzarlo:
el parte queda en borrador hasta completarlos) y el autocheck-in exige el check-in online.

> **Verificar antes de producción:** el espacio de nombres del XML de reservas (`app.ses.rh-namespace`,
> por defecto `http://www.neg.hospedajes.mir.es/altaReservaHospedaje`) solo aparece en los diagramas XSD del
> documento oficial; confírmelo contra el XSD en el entorno de pruebas (`hospedajes.pre-ses.mir.es`).

## Llave virtual (TTLock)

| Endpoint | Rol | Descripción |
|---|---|---|
| `GET /api/v1/guest/doors` | huésped | Estado de los botones y del autocheck-in |
| `POST /api/v1/guest/check-in` | huésped | Autocheck-in el día de llegada (unidad lista y saldo pagado) |
| `POST /api/v1/guest/doors/main-entrance/unlock` | huésped | «Abrir Entrada Principal» |
| `POST /api/v1/guest/doors/room/unlock` | huésped | «Abrir Mi Habitación» |
| `/api/v1/admin/locks` (+ `/provider`, `/sync`) | ADMIN | Alta de cerraduras, descubrimiento en la cuenta TTLock y sincronización de batería |
| `POST /api/v1/backoffice/locks/{id}/unlock` | RECEPTIONIST | Apertura remota por el personal |
| `GET /api/v1/backoffice/locks/{id}/access-log` · `/reservations/{id}/access-log` | RECEPTIONIST | Auditoría de aperturas |

Cada apertura se valida en el servidor: JWT de huésped y enlace vigente → reserva `CHECKED_IN` → dentro de
la ventana de estancia (día de llegada a la hora de check-in, hasta la hora de salida + `app.access.late-grace`)
→ cerradura activa asignada → límite por reserva. Solo entonces se llama a TTLock (servidor a servidor, vía
gateway). Todos los intentos —correctos, denegados y fallidos— quedan en `lock_access_log` con IP y latencia.

El token OAuth de TTLock se guarda cifrado (AES-256-GCM, `APP_CREDENTIALS_KEY`) y se renueva solo; si TTLock lo
rechaza, se renueva y se reintenta una vez. Requisitos físicos: cada cerradura emparejada con un gateway
Wi-Fi (G2/G3) y la apertura remota activada en la app TTLock.

## Channel manager (Beds24 → Booking.com, Airbnb, Expedia, Google Hotel Center)

El PMS es la fuente de verdad del inventario y de las tarifas; Beds24 los distribuye a las OTAs conectadas
(Google Hotel Center se conecta en Beds24 como un canal más) y devuelve sus reservas.

**Vinculación** (catálogo, rol ADMIN): `beds24RoomId` en la tipología, `beds24UnitId` en cada unidad y
`channelRateCode = price1…price16` en los planes (hueco de precio de Beds24). Sin códigos, el plan por defecto
se envía como `price1`. Las restricciones (estancia mínima/máxima, CTA, CTD, stop-sell) son por habitación en
Beds24 y se toman del plan de `price1` o, en su defecto, del plan por defecto.

**Salida (outbox, con reintentos y espera exponencial; el 429 por límite de créditos se reintenta):**
- `inventory.sync`: unidades libres, precios por hueco y restricciones día a día (`from = to`) de las noches
  afectadas por reservas, retenciones del checkout, bloqueos, unidades, planes, temporadas y ajustes diarios.
  Stop-sell → `blackout`; CTA/CTD → `noCheckIn`/`noCheckOut`/`noCheckInOrCheckOut`. Resincronización completa
  cada noche (`app.jobs.beds24-full-sync-cron`) y bajo demanda.
- `booking.push`: las reservas propias (web, teléfono, mostrador) se crean/modifican/cancelan en Beds24 con el
  localizador como `apiReference`.

**Entrada:** webhook `POST|GET /api/v1/webhooks/beds24` autenticado con la cabecera
`X-Ochavada-Webhook-Token` (en Beds24: *Ajustes → Alojamiento → Webhooks*, versión *2 sin datos personales*,
cabecera personalizada `X-Ochavada-Webhook-Token: <BEDS24_WEBHOOK_TOKEN>`). El cuerpo solo identifica la
reserva: su estado se lee de la API (fetch-on-notify) y se importa desde el outbox. Un sondeo incremental
(`app.jobs.beds24-poll-interval`, `modifiedFrom`) cubre los webhooks perdidos.

Reglas de importación: las reservas de OTA nunca se rechazan. Se asigna la unidad indicada por Beds24 si está
libre, si no otra libre; si no queda ninguna, la reserva queda sin unidad y con aviso `channelSyncNote`
(posible sobreventa). Modificaciones y cancelaciones se aplican solas salvo las que no pueden aplicarse con el
huésped alojado (se anotan para recepción). El eco de las reservas propias se ignora. Pago `OTA_COLLECT` para
los canales de `BEDS24_OTA_COLLECT_CHANNELS` (por defecto Airbnb y Expedia), `AT_PROPERTY` para el resto.

| Endpoint (ADMIN) | Descripción |
|---|---|
| `GET /api/v1/admin/channel/beds24/status` | Conexión, último sondeo, eventos pendientes/fallidos y vinculación |
| `POST /api/v1/admin/channel/beds24/connect` | Canjea el código de invitación de Beds24 (refresh token cifrado) |
| `GET /api/v1/admin/channel/beds24/rooms` | Habitaciones y unidades de Beds24 con su vinculación |
| `POST /api/v1/admin/channel/beds24/sync` | Reenvía todo el horizonte (`BEDS24_SYNC_HORIZON_DAYS`) |
| `POST /api/v1/admin/channel/beds24/poll` · `/import-upcoming` | Sondeo inmediato · importación inicial de reservas futuras |
| `POST /api/v1/admin/channel/beds24/bookings/{id}/import` | Reimporta una reserva concreta |

Pendiente de verificar con una cuenta real de Beds24 (la documentación pública no lo fija): formato de
`modifiedFrom`, si `numAvail` del calendario sustituye o se resta de las reservas de Beds24, `maxStay = 0` como
«sin máximo» y la forma exacta del cuerpo del webhook (se admiten `booking.id`, `id` y `bookId`).

Configuración en Stripe: endpoint `https://<api>/api/v1/webhooks/stripe` con los eventos
`payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.processing`,
`payment_intent.requires_action` y `payment_intent.canceled`.

## Variables de entorno

Ver [`.env.example`](.env.example). `JWT_SECRET` es obligatoria fuera del perfil `dev`
(`openssl rand -base64 48`).
