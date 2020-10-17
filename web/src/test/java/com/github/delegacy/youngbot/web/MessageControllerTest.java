package com.github.delegacy.youngbot.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;

import com.github.delegacy.youngbot.internal.testing.TestConfiguration;
import com.github.delegacy.youngbot.message.MessageRequest;
import com.github.delegacy.youngbot.message.MessageResponse;
import com.github.delegacy.youngbot.message.MessageService;

import reactor.core.publisher.Flux;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TestConfiguration.class)
@WebFluxTest(MessageControllerTest.TestMessageController.class)
class MessageControllerTest {
    @RestController
    @RequestMapping("/api/message/v1")
    static class TestMessageController extends AbstractMessageController {
        @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
        protected TestMessageController(MessageService messageService) {
            super(messageService);
        }
    }

    @MockBean
    private MessageService messageService;

    @Resource
    private WebTestClient webClient;

    @Test
    void testOnWebhook() {
        when(messageService.process(any())).thenReturn(
                Flux.just(MessageResponse.of(MessageRequest.of("text", "channel"), "PONG")));

        webClient.post().uri("/api/message/v1/webhook")
                 .contentType(MediaType.APPLICATION_JSON)
                 .body(BodyInserters.fromValue("{\"text\":\"ping\"}"))
                 .exchange()
                 .expectStatus().isOk()
                 .expectBody()
                 .json("[{\"text\":\"PONG\"}]");
    }

    @Test
    void testOnWebhook_multipleWebhookResponses() {
        when(messageService.process(any())).thenReturn(
                Flux.just(MessageResponse.of(MessageRequest.of("text", "channel"), "PONG"))
                    .repeat(2));

        webClient.post().uri("/api/message/v1/webhook")
                 .contentType(MediaType.APPLICATION_JSON)
                 .body(BodyInserters.fromValue("{\"text\":\"ping\"}"))
                 .exchange()
                 .expectStatus().isOk()
                 .expectBody()
                 .json("[{\"text\":\"PONG\"},{\"text\":\"PONG\"},{\"text\":\"PONG\"}]");
    }

    @Test
    void testOnWebhook_badRequestException() {
        webClient.post().uri("/api/message/v1/webhook")
                 .contentType(MediaType.APPLICATION_JSON)
                 .body(BodyInserters.fromValue("{}"))
                 .exchange()
                 .expectStatus().isBadRequest();
    }

    @Test
    void testOnWebhook_internalServerErrorException() {
        when(messageService.process(any())).thenThrow(RuntimeException.class);

        webClient.post().uri("/api/message/v1/webhook")
                 .contentType(MediaType.APPLICATION_JSON)
                 .body(BodyInserters.fromValue("{\"text\":\"ping\"}"))
                 .exchange()
                 .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}