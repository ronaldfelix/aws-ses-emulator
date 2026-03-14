package org.aref.fakeses.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aref.fakeses.service.EmailRelayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SesController {

    private final EmailRelayService emailRelayService;

    /**
     * Punto de entrada principal.
     * El AWS SDK v2 envía: POST / con Action=SendRawEmail | SendEmail | ...
     * Responde con XML idéntico al de AWS SES para que el SDK no detecte diferencia.
     */
    @PostMapping(
            value = "/",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_XML_VALUE
    )
    public ResponseEntity<String> handleSesQueryApi(
            @RequestParam MultiValueMap<String, String> params) {

        String action = params.getFirst("Action");
        log.info("[SES-EMULATOR] Action: {}", action);

        try {
            return switch (action != null ? action : "") {
                case "SendRawEmail" -> processSendRawEmail(params);
                case "SendEmail"    -> processSendEmail(params);
                default -> {
                    log.info("[SES-EMULATOR] Action '{}' → respuesta genérica OK", action);
                    yield ResponseEntity.ok(buildGenericResponse(action));
                }
            };
        } catch (Exception e) {
            log.error("[SES-EMULATOR] Error en action '{}': {}", action, e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorXml("InternalFailure", e.getMessage()));
        }
    }

    //SendRawEmail
    private ResponseEntity<String> processSendRawEmail(MultiValueMap<String, String> params) {
        try {
            String rawBase64 = params.getFirst("RawMessage.Data");
            if (rawBase64 == null || rawBase64.isBlank()) {
                throw new IllegalArgumentException("RawMessage.Data está vacío");
            }
            byte[] rawBytes = Base64.getMimeDecoder().decode(rawBase64);
            String messageId = emailRelayService.sendRawEmail(rawBytes);
            log.info("[SES-EMULATOR] SendRawEmail OK → messageId: {}", messageId);
            return ResponseEntity.ok(buildSendRawEmailResponse(messageId));
        } catch (Exception e) {
            log.error("[SES-EMULATOR] SendRawEmail falló: {}", e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorXml("MessageRejected", e.getMessage()));
        }
    }

    // SendEmail
    private ResponseEntity<String> processSendEmail(MultiValueMap<String, String> params) {
        try {
            String messageId = emailRelayService.sendStructuredEmail(params);
            log.info("[SES-EMULATOR] SendEmail OK → messageId: {}", messageId);
            return ResponseEntity.ok(buildSendEmailResponse(messageId));
        } catch (Exception e) {
            log.error("[SES-EMULATOR] SendEmail falló: {}", e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorXml("MessageRejected", e.getMessage()));
        }
    }

    // ── XML Response Builders (formato idéntico a AWS SES)

    private String buildSendRawEmailResponse(String messageId) {
        return """
                <SendRawEmailResponse xmlns="http://ses.amazonaws.com/doc/2010-12-01/">
                  <SendRawEmailResult>
                    <MessageId>%s</MessageId>
                  </SendRawEmailResult>
                  <ResponseMetadata>
                    <RequestId>%s</RequestId>
                  </ResponseMetadata>
                </SendRawEmailResponse>
                """.formatted(messageId, UUID.randomUUID());
    }

    private String buildSendEmailResponse(String messageId) {
        return """
                <SendEmailResponse xmlns="http://ses.amazonaws.com/doc/2010-12-01/">
                  <SendEmailResult>
                    <MessageId>%s</MessageId>
                  </SendEmailResult>
                  <ResponseMetadata>
                    <RequestId>%s</RequestId>
                  </ResponseMetadata>
                </SendEmailResponse>
                """.formatted(messageId, UUID.randomUUID());
    }

    private String buildGenericResponse(String action) {
        String tag = (action != null ? action : "Generic") + "Response";
        return """
                <%s xmlns="http://ses.amazonaws.com/doc/2010-12-01/">
                  <ResponseMetadata>
                    <RequestId>%s</RequestId>
                  </ResponseMetadata>
                </%s>
                """.formatted(tag, UUID.randomUUID(), tag);
    }

    private String buildErrorXml(String code, String message) {
        String safeMsg = message != null ? message.replace("&", "&amp;").replace("<", "&lt;") : "Error";
        return """
                <ErrorResponse xmlns="http://ses.amazonaws.com/doc/2010-12-01/">
                  <Error>
                    <Type>Sender</Type>
                    <Code>%s</Code>
                    <Message>%s</Message>
                  </Error>
                  <RequestId>%s</RequestId>
                </ErrorResponse>
                """.formatted(code, safeMsg, UUID.randomUUID());
    }
}

