package org.aref.fakeses.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.*;

@Slf4j
@Service
public class EmailRelayService {

    @Value("${ses.emulator.gmail.user}")
    private String gmailUser;

    @Value("${ses.emulator.gmail.app-password}")
    private String gmailAppPassword;

    private Session gmailSession;

    /**
     * Crea y verifica la sesión Gmail al arrancar el contenedor.
     * Si las credenciales son incorrectas, el proceso falla rápido (fail-fast).
     */
    @PostConstruct
    public void init() {
        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        gmailSession = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(gmailUser, gmailAppPassword);
            }
        });

        try (Transport transport = gmailSession.getTransport("smtp")) {
            transport.connect("smtp.gmail.com", 587, gmailUser, gmailAppPassword);
            log.info("[SES-EMULATOR] Conexión a Gmail SMTP verificada (cuenta: {})", gmailUser);
        } catch (MessagingException e) {
            log.error("[SES-EMULATOR] No se pudo conectar a Gmail SMTP: {}", e.getMessage());
            throw new IllegalStateException(
                    "Error conectando a Gmail SMTP. Verifica GMAIL_USER y GMAIL_APP_PASSWORD.", e);
        }
    }

    /**
     * Procesa un SendRawEmail: el MIME completo ya decodificado desde Base64.
     * Spring Cloud AWS (SesJavaMailSender) siempre usa esta vía.
     *
     * @param rawBytes bytes del mensaje MIME original
     * @return messageId simulado (formato AWS SES)
     */
    public String sendRawEmail(byte[] rawBytes) throws MessagingException, UnsupportedEncodingException {
        MimeMessage originalMsg = new MimeMessage(gmailSession, new ByteArrayInputStream(rawBytes));

        Address[] allRecipients = originalMsg.getAllRecipients();
        if (allRecipients == null || allRecipients.length == 0) {
            throw new MessagingException("No se encontraron destinatarios en el mensaje MIME");
        }

        log.info("[SES-EMULATOR] SendRawEmail → Para: {} | Asunto: {}",
                Arrays.toString(allRecipients), originalMsg.getSubject());

        warnIfFromDiffers(originalMsg.getFrom());

        MimeMessage relayMsg = new MimeMessage(gmailSession, new ByteArrayInputStream(rawBytes));
        relayMsg.setFrom(new InternetAddress(gmailUser, "AWS SES Local Emulator", "UTF-8"));

        try (Transport transport = gmailSession.getTransport("smtp")) {
            transport.connect("smtp.gmail.com", 587, gmailUser, gmailAppPassword);
            transport.sendMessage(relayMsg, allRecipients);
        }

        String messageId = generateSesMessageId();
        log.info("[SES-EMULATOR] Correo enviado. SES-MessageId: {}", messageId);
        return messageId;
    }

    /**
     * Procesa un SendEmail donde los campos vienen como parámetros HTTP individuales.
     * Ej: Source, Destination.ToAddresses.member.1, Message.Subject.Data, etc.
     *
     * @param params parámetros del formulario HTTP
     * @return messageId simulado
     */
    public String sendStructuredEmail(MultiValueMap<String, String> params) throws MessagingException, UnsupportedEncodingException {
        String from    = params.getFirst("Source");
        String subject = params.getFirst("Message.Subject.Data");
        String html    = params.getFirst("Message.Body.Html.Data");
        String text    = params.getFirst("Message.Body.Text.Data");

        List<String> toList  = extractMemberList(params, "Destination.ToAddresses.member.");
        List<String> ccList  = extractMemberList(params, "Destination.CcAddresses.member.");
        List<String> bccList = extractMemberList(params, "Destination.BccAddresses.member.");

        log.info("[SES-EMULATOR] SendEmail → Para: {} | Asunto: {}", toList, subject);

        if (from != null && !from.isBlank()) {
            warnIfFromDiffers(from);
        }

        MimeMessage message = new MimeMessage(gmailSession);

        message.setFrom(new InternetAddress(gmailUser, "AWS SES Local Emulator", "UTF-8"));

        if (from != null && !from.isBlank()) {
            message.setReplyTo(InternetAddress.parse(from));
        }

        message.setRecipients(Message.RecipientType.TO,
                toList.stream().map(this::toInternetAddress).toArray(Address[]::new));

        if (!ccList.isEmpty()) {
            message.setRecipients(Message.RecipientType.CC,
                    ccList.stream().map(this::toInternetAddress).toArray(Address[]::new));
        }
        if (!bccList.isEmpty()) {
            message.setRecipients(Message.RecipientType.BCC,
                    bccList.stream().map(this::toInternetAddress).toArray(Address[]::new));
        }

        message.setSubject(subject, "UTF-8");

        if (html != null && !html.isBlank()) {
            message.setContent(html, "text/html; charset=UTF-8");
        } else if (text != null && !text.isBlank()) {
            message.setContent(text, "text/plain; charset=UTF-8");
        }

        Address[] allRecipients = message.getAllRecipients();
        try (Transport transport = gmailSession.getTransport("smtp")) {
            transport.connect("smtp.gmail.com", 587, gmailUser, gmailAppPassword);
            transport.sendMessage(message, allRecipients);
        }

        String messageId = generateSesMessageId();
        log.info("[SES-EMULATOR] Correo enviado. SES-MessageId: {}", messageId);
        return messageId;
    }

    /**
     * Logea una advertencia si el From del mensaje MIME original (enviado por el micro)
     * no coincide con la cuenta Gmail configurada en el emulador.
     */
    private void warnIfFromDiffers(Address[] originalFrom) {
        if (originalFrom == null || originalFrom.length == 0) return;
        String fromEmail = originalFrom[0] instanceof InternetAddress ia
                ? ia.getAddress()
                : originalFrom[0].toString();
        warnIfFromDiffers(fromEmail);
    }

    private void warnIfFromDiffers(String originalFrom) {
        if (originalFrom == null || originalFrom.isBlank()) return;
        String cleanFrom = originalFrom.contains("<")
                ? originalFrom.substring(originalFrom.indexOf('<') + 1, originalFrom.indexOf('>'))
                : originalFrom.trim();
        if (!cleanFrom.equalsIgnoreCase(gmailUser)) {
            log.warn("[SES-EMULATOR] El 'From' del microservicio ({}) difiere del Gmail relay ({}). "
                            + "Gmail sobrescribirá el remitente con '{}'. En producción (AWS SES real) "
                            + "se usará '{}' como From.",
                    cleanFrom, gmailUser, gmailUser, cleanFrom);
        }
    }

    /** Extrae listas con formato AWS: member.1, member.2, ... */
    private List<String> extractMemberList(MultiValueMap<String, String> params, String prefix) {
        List<String> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + i);
            if (value == null) break;
            result.add(value.trim());
        }
        return result;
    }

    private InternetAddress toInternetAddress(String email) {
        try {
            return new InternetAddress(email);
        } catch (Exception e) {
            throw new RuntimeException("Email inválido: " + email, e);
        }
    }

    /** Genera un MessageId con el mismo formato que usa AWS SES real. */
    private String generateSesMessageId() {
        String u = UUID.randomUUID().toString().replace("-", "");
        return String.format("0000000000000000-%s-%s-%s-%s-%s-000000",
                u.substring(0, 8),
                u.substring(8, 12),
                u.substring(12, 16),
                u.substring(16, 20),
                u.substring(20, 32));
    }
}




