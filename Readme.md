# 🛠️ fakeses — Emulador Local de AWS SES

> **Stack:** Spring Boot 4 · Gradle · Java 21 · Jakarta Mail · Docker  
> **Objetivo:** Servicio Java que **expone el mismo endpoint HTTP que AWS SES**, para que cualquier aplicación que use el AWS SDK v2 apunte a `localhost` con solo cambiar una URL en sus configuraciones. Internamente reenvía los correos mediante **Gmail SMTP** para entrega real.

---

## ⚡ TL;DR — ¿Qué necesito cambiar en mi aplicación cliente?

**Solo agregar estas líneas en el perfil local de tu app. Cero cambios de código Java.**

```yaml
# Propiedades de tu app
spring.cloud.aws.credentials.access-key=cualquier-cosa
spring.cloud.aws.credentials.secret-key=algo-random
spring.cloud.aws.ses.endpoint=http://localhost:8045 (puerto definido)<--importante
```

Para el caso del `from`, tambien poner algo random ya que el real se definira en las variables de server

```yaml
# Propiedades de tu app
email.aws-ses.from: hola@.com
```

Con el emulador corriendo, al disparar un envío de correo:

```
Tu app  ──(AWS SDK v2)──►  POST http://localhost:8045/  ──(Gmail SMTP)──►  destinatario real
                            Action=SendRawEmail
                            (no toca AWS en ningún momento)
```

> **¿Por qué credenciales fake?**  
> Si tu `configuracion` define `${AWS_ACCESS_KEY_ID}` y `${AWS_SECRET_ACCESS_KEY}`
> **sin valor por defecto**, Spring no arranca sin ellos.
> El emulador ignora completamente la firma Signature V4 de AWS — cualquier valor sirve.

---

## 📐 Arquitectura

```
┌────────────────────────────────────────────────────────────────────┐
│  Entorno Local                                                     │
│                                                                    │
│  ┌──────────────────────────┐   HTTP POST /                        │
│  │                          │   Action=SendRawEmail                │
│  │   Tu aplicación          │──────────────────────►┌───────────┐ │
│  │   (cualquier app que     │   AWS SDK v2           │  fakeses  │ │
│  │    use SesClient)        │   (SesClient)          │  emulator │ │
│  │                          │                        │  :8045    │ │
│  │  ses.endpoint:           │                        │           │ │
│  │   http://localhost:8045  │◄──────────────────────┤           │ │
│  └──────────────────────────┘   XML response         └─────┬─────┘ │
│                                 (imita AWS SES)             │       │
└─────────────────────────────────────────────────────────────│───────┘
                                                              │ Gmail SMTP
                                                              ▼ TLS :587
                                                   ┌──────────────────┐
                                                   │  smtp.gmail.com  │
                                                   │  App Password    │
                                                   └────────┬─────────┘
                                                            ▼
                                                   📧 Destinatario real
```

**Flujo exacto:**
1. Tu app llama a `JavaMailSender.send()` (o usa `SesClient` directamente).
2. `SesJavaMailSender` (Spring Cloud AWS) convierte el `MimeMessage` a `SendRawEmailRequest`.
3. El `SesClient` (AWS SDK v2) hace `POST http://localhost:8045/` con `Action=SendRawEmail&RawMessage.Data=<base64>`.
4. El emulador parsea el MIME, reenvía vía Gmail y responde con XML idéntico al de AWS SES.
5. El SDK procesa la respuesta sin detectar diferencia alguna con AWS real.

**Cambio mínimo en tu app:** solo YAML. **Cero cambios en código Java.**

---

## 📋 Prerrequisitos

| Herramienta           | Versión mínima | Uso                                    |
|-----------------------|----------------|----------------------------------------|
| Docker Desktop        | 24.x           | Correr el emulador                     |
| Docker Compose        | 2.x            | Orquestar servicios locales            |
| JDK (solo desarrollo) | 21             | Compilar el emulador fuera de Docker   |
| Gradle (wrapper incl.)| 8.x            | Build fuera de Docker                  |
| Cuenta Gmail          | —              | App Password para envío real de correos|

> Si usas Docker Compose para todo, **no necesitas JDK ni Gradle** instalados localmente.

---

## 🔐 Configurar Gmail: App Password

1. Ve a tu cuenta Google → **Seguridad** → activa **Verificación en 2 pasos**.
2. Ve a [https://myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)
3. Crea una App Password nueva:
   - Nombre: `fakeses` (cualquiera sirve)
   - Copia los **16 caracteres** generados (ej: `abcd efgh ijkl mnop`). y quitar espacios → `abcdefghijklmnop`
4. Guárdala esas variables y guardalas en un .env o los defines en tu entorno local:

```bash
GMAIL_USER=correo@gmail.com
GMAIL_PASSWORD=abcdefghijklmnop
SES_EMULATOR_PORT=8045 # Opcional, el default es 8045
```

---

## 📁 Estructura del Proyecto

```
fakeses/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── Dockerfile
├── .dockerignore
├── .env (solo desarrollo)
├── .gitignore
└── src/
    └── main/
        ├── java/org/aref/fakeses/
        │   ├── FakesesApplication.java
        │   ├── web/
        │   │   └── SesController.java
        │   └── service/
        │       └── EmailRelayService.java
        └── resources/
            └── application.yml
```

---

## ⚙️ Configuración del emulador — `application.yml`

```yaml
spring:
  application:
    name: fakeses

server:
  port: ${SES_EMULATOR_PORT:8045}

# OBLIGATORIO: definir GMAIL_USER y GMAIL_APP_PASSWORD como variables de entorno.
ses:
  emulator:
    gmail:
      user: ${GMAIL_USER}
      app-password: ${GMAIL_APP_PASSWORD}

logging:
  level:
    root: INFO
    org.aref.fakeses: DEBUG
```

---

## ⚙️ Cambios en tu aplicación cliente — Solo YAML

> **¿Como es que funciona?**
>
> `spring.cloud.aws.ses.endpoint` mapea a `SesProperties.endpoint` (heredado de `AwsClientProperties`).
> Spring Cloud AWS llama a `SesClient.Builder.endpointOverride(endpoint)` en la autoconfiguration.
> El `SesClient` resultante ya apunta al emulador. `SesJavaMailSender` lo usa internamente.
> Tu servicio de email recibe el mismo bean `JavaMailSender` **sin enterarse del cambio**.

### Test directo con curl

```bash
# Simular lo que haría el AWS SDK con un SendRawEmail mínimo
MIME_RAW=$(printf 'From: test@local.test\r\nTo: destinatario@ejemplo.com\r\nSubject: Test fakeses\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<h1>Hola desde fakeses 🚀</h1>' | base64 -w 0)

curl -s -X POST http://localhost:8045/ \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "Action=SendRawEmail" \
  --data-urlencode "Version=2010-12-01" \
  --data-urlencode "RawMessage.Data=${MIME_RAW}"
```

**Respuesta esperada:**
```xml
<SendRawEmailResponse xmlns="http://ses.amazonaws.com/doc/2010-12-01/">
  <SendRawEmailResult>
    <MessageId>0000000000000000-abc12345-abcd-efgh-ijkl-abcdef012345-000000</MessageId>
  </SendRawEmailResult>
  <ResponseMetadata>
    <RequestId>xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</RequestId>
  </ResponseMetadata>
</SendRawEmailResponse>
```

### Logs del emulador esperados

```
[SES-EMULATOR] Conexión a Gmail SMTP verificada (cuenta: tu@gmail.com)
[SES-EMULATOR] Action: SendRawEmail
[SES-EMULATOR] SendRawEmail → Para: [destinatario@ejemplo.com] | Asunto: Test fakeses
[SES-EMULATOR] El 'From' del microservicio (test@local.test) difiere del Gmail relay (tu@gmail.com)...
[SES-EMULATOR] Correo enviado. SES-MessageId: 0000000000000000-...
```

---

## 🗺️ Resumen de variables de entorno

### Emulador (`fakeses/.env`)

| Variable             | Obligatoria | Descripción                                  | Ejemplo            |
|----------------------|---------|----------------------------------------------|--------------------|
| `GMAIL_USER`         | Sí      | Cuenta Gmail que envía correos reales        | `yo@gmail.com`     |
| `GMAIL_APP_PASSWORD` | Sí      | App Password Google (16 chars, sin espacios) | `abcdefghijklmnop` |
| `SES_EMULATOR_PORT`  | No      | Puerto HTTP del emulador (default: `8045`)   | `8045`             |

## 🔍 Troubleshooting

### `535-5.7.8 Username and Password not accepted` (Gmail)
- Verifica que la **App Password** sea correcta (sin espacios, exactamente 16 chars).
- Confirma que la cuenta Gmail tiene **Verificación en 2 pasos** activada.
- Link: [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)

### El AWS SDK sigue llamando a `email.us-east-1.amazonaws.com`
- Confirma que `spring.cloud.aws.ses.endpoint` está en tu perfil local.
- Confirma que el perfil activo es `local`: `SPRING_PROFILES_ACTIVE=local`.
- Activa `logging.level.software.amazon.awssdk.request: DEBUG` para ver las llamadas HTTP.

### `SdkClientException: Unable to load credentials`
- El SDK necesita credenciales aunque sea el endpoint local.
- Asegúrate de que `spring.cloud.aws.credentials.access-key` y `secret-key` están definidos en tu perfil local o vía variables de entorno.

### `Connection refused` al emulador desde Docker Compose
- Tu app debe usar `SES_ENDPOINT=http://ses-emulator:8045` (nombre del servicio Docker), no `localhost`.
- Verifica que ambos servicios estén en la misma network.

### El correo llega pero el `From` muestra la cuenta Gmail
- Gmail obliga que el `From` sea la cuenta autenticada. Esto es correcto y esperado en local.
- El emulador logea un `⚠️ WARN` cuando el `From` original difiere del Gmail relay.
- Se configura `Reply-To` con el from original para que el destinatario pueda responder al remitente real.
- En producción (AWS SES real), el `From` sí será el configurado en tu app.

### `IllegalStateException: Error conectando a Gmail SMTP` al arrancar
- El emulador hace **fail-fast** si las credenciales son incorrectas.
- Revisa los logs: `docker logs fakeses`.

### `Local address contains illegal character`
- El campo `from` de tu app espera un **email** (ej: `no-reply@local.test`), no una URL.
- La URL del emulador (`http://localhost:8045`) va en `spring.cloud.aws.ses.endpoint`, no en `from`.

---

## Checklist

- [ ] Establecer variables de entorno
- [ ] Levantar el emulador
- [ ] Verificar log: `Conexión a Gmail SMTP verificada`
- [ ] Agregar en el perfil local de tu app: `spring.cloud.aws.ses.endpoint` + credenciales fake
- [ ] Disparar un envío de correo y confirmar el log `Correo enviado`
- [ ] Verificar la bandeja de entrada del destinatario

---
> Saludos :)
