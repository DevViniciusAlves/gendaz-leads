package com.gendaz.leads.controller;

import com.gendaz.leads.dto.whatsapp.WhatsAppSendRequest;
import com.gendaz.leads.whatsapp.WhatsAppQr;
import com.gendaz.leads.whatsapp.WhatsAppSendResult;
import com.gendaz.leads.whatsapp.WhatsAppService;
import com.gendaz.leads.whatsapp.WhatsAppSessionStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API autenticada do WhatsApp para o frontend.
 * O frontend chama SOMENTE o Spring; o Spring repassa ao whatsapp-service (Node).
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {

    private final WhatsAppService whatsAppService;

    public WhatsAppController(WhatsAppService whatsAppService) {
        this.whatsAppService = whatsAppService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        WhatsAppSessionStatus s = whatsAppService.status();
        return ResponseEntity.ok(Map.of(
                "status", s.status(),
                "hasQr", s.hasQr(),
                "lastError", s.lastError() == null ? "" : s.lastError()));
    }

    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect() {
        WhatsAppSessionStatus s = whatsAppService.connect();
        return ResponseEntity.ok(Map.of(
                "status", s.status(),
                "hasQr", s.hasQr()));
    }

    @GetMapping("/qr")
    public ResponseEntity<Map<String, Object>> qr() {
        WhatsAppQr qr = whatsAppService.qr();
        return ResponseEntity.ok(Map.of(
                "qr", qr.qr() == null ? "" : qr.qr(),
                "updatedAt", qr.updatedAt() == null ? "" : qr.updatedAt()));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        WhatsAppSessionStatus s = whatsAppService.disconnect();
        return ResponseEntity.ok(Map.of("status", s.status()));
    }

    /** Envio tecnico individual (1 destinatario, 1 texto). Fila/delay comercial ficam na proxima task. */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@Valid @RequestBody WhatsAppSendRequest request) {
        WhatsAppSendResult r = whatsAppService.sendText(
                request.getRecipient(), request.getText(), request.getRequestId());
        return ResponseEntity.ok(Map.of(
                "sent", r.sent(),
                "messageId", r.messageId() == null ? "" : r.messageId(),
                "requestId", r.requestId() == null ? "" : r.requestId(),
                "deduplicated", r.deduplicated()));
    }
}
