package com.github.delegacy.youngbot.server.line;

import static com.github.delegacy.youngbot.server.line.LineJacksonUtils.deserialize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.RequestEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.github.delegacy.youngbot.server.RequestContext;
import com.github.delegacy.youngbot.server.message.service.MessageService;
import com.github.delegacy.youngbot.server.platform.Platform;

import com.linecorp.bot.model.event.CallbackRequest;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.MessageContent;
import com.linecorp.bot.model.event.message.TextMessageContent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/line/v1")
public class LineController {
    private final LineSignatureValidator lineSignatureValidator;

    private final MessageService messageService;

    @PostMapping("/webhook")
    public Mono<String> onWebhook(RequestEntity<String> req, ServerWebExchange exchange) {

        final List<String> signatures = req.getHeaders().get("X-Line-Signature");
        if (CollectionUtils.isEmpty(signatures)) {
            log.warn("No X-Line-Signature");
            return Mono.just("");
        }

        final String signature = signatures.get(0);
        final String payload = req.getBody();
        log.debug("Received LINE webhook;signature<{}>,payload<{}>", signature, payload);

        final CallbackRequest callbackRequest = parsePayload(signature, payload);
        callbackRequest.getEvents().forEach(event -> {
            if (!(event instanceof MessageEvent)) {
                return;
            }

            @SuppressWarnings("rawtypes")
            final MessageEvent messageEvent = (MessageEvent) event;
            final MessageContent messageContent = messageEvent.getMessage();
            if (!(messageContent instanceof TextMessageContent)) {
                return;
            }

            final TextMessageContent textMessageContent = (TextMessageContent) messageContent;

            final RequestContext ctx = new RequestContext(Platform.LINE, exchange,
                                                          textMessageContent.getText(),
                                                          messageEvent.getReplyToken());

            messageService.process(ctx, textMessageContent.getText());
        });

        return Mono.just("");
    }

    private CallbackRequest parsePayload(String signature, String payload) {
        if (signature == null || signature.isEmpty()) {
            throw new IllegalArgumentException("No signature");
        }

        final byte[] json = payload.getBytes(StandardCharsets.UTF_8);
        if (!lineSignatureValidator.validateSignature(json, signature)) {
            throw new IllegalArgumentException("Invalid signature");
        }

        final CallbackRequest callbackRequest = deserialize(json, CallbackRequest.class);
        if (callbackRequest == null || callbackRequest.getEvents() == null) {
            throw new IllegalArgumentException("Invalid content");
        }

        return callbackRequest;
    }
}
