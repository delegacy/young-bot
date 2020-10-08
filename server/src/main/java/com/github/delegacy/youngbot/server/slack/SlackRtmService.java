package com.github.delegacy.youngbot.server.slack;

import static com.github.delegacy.youngbot.server.ReactorContextFilter.REQUEST_ID_KEY;
import static java.util.Objects.requireNonNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

import javax.inject.Inject;
import javax.websocket.CloseReason.CloseCodes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.github.delegacy.youngbot.server.message.MessageContext;
import com.github.delegacy.youngbot.server.message.handler.MessageHandlerManager;
import com.slack.api.bolt.App;
import com.slack.api.model.event.HelloEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.rtm.RTMClient;
import com.slack.api.rtm.RTMEventHandler;
import com.slack.api.rtm.RTMEventsDispatcher;
import com.slack.api.rtm.RTMEventsDispatcherFactory;
import com.slack.api.rtm.message.Message;
import com.slack.api.rtm.message.PingMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

@Service
@ConditionalOnProperty(value = "youngbot.slack.rtm.enabled", havingValue = "true")
class SlackRtmService implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(SlackRtmService.class);

    private static final Random RANDOM = new Random();

    private final AtomicLong msgId = new AtomicLong();

    private final ScheduledExecutorService executorService;

    private final RTMClient rtmClient;

    private final MessageHandlerManager messageHandlerManager;

    @Inject
    SlackRtmService(App app, MessageHandlerManager messageHandlerManager) throws Exception {
        this(app, messageHandlerManager, Executors.newSingleThreadScheduledExecutor());
    }

    SlackRtmService(App app, MessageHandlerManager messageHandlerManager,
                    ScheduledExecutorService executorService) throws Exception {
        this.messageHandlerManager = requireNonNull(messageHandlerManager, "messageHandlerManager");
        this.executorService = requireNonNull(executorService, "executorService");

        rtmClient = requireNonNull(app, "app").slack().rtmConnect(app.config().getSingleTeamBotToken());
        configureRtmClient();
    }

    private void configureRtmClient() throws Exception {
        final RTMEventsDispatcher dispatcher = RTMEventsDispatcherFactory.getInstance();
        dispatcher.register(new HelloEventHandler());
        dispatcher.register(new MessageEventHandler());

        rtmClient.addMessageHandler(dispatcher.toMessageHandler());
        rtmClient.addErrorHandler(t -> logger.warn("A RTM session error occurred.", t));
        rtmClient.addCloseHandler(reason -> {
            if (reason.getCloseCode() == CloseCodes.NORMAL_CLOSURE) {
                logger.info("The RTM session is closed because of {}.", reason);
            } else {
                logger.error("The RTM session is closed because of {}.", reason);
            }
        });

        rtmClient.reconnect();
    }

    @Override
    public void close() throws IOException {
        executorService.shutdown();

        rtmClient.close();
    }

    class HelloEventHandler extends RTMEventHandler<HelloEvent> {
        @Override
        public void handle(HelloEvent event) {
            executorService.scheduleAtFixedRate(new PingTask(), 1L, 30L, TimeUnit.SECONDS);
        }
    }

    class PingTask implements Runnable {
        @Override
        public void run() {
            try {
                rtmClient.sendMessage(PingMessage.builder()
                                                 .id(msgId.incrementAndGet())
                                                 .build()
                                                 .toJSONString());
            } catch (RuntimeException e) {
                logger.warn("Failed to ping Slack", e);
            }
        }
    }

    class MessageEventHandler extends RTMEventHandler<MessageEvent> {
        @Override
        public void handle(MessageEvent event) {
            logger.debug("Received text<{}> from channel<{}>", event.getText(), event.getChannel());

            final MessageContext msgCtx =
                    new SlackMessageContext(event.getText(), event.getChannel());

            Flux.fromIterable(messageHandlerManager.handlers())
                .concatMap(handler -> {
                    final Matcher matcher = handler.pattern().matcher(msgCtx.text());
                    if (!matcher.matches()) {
                        return Flux.empty();
                    }
                    return handler.handle(msgCtx, matcher);
                })
                .filter(s -> !s.isEmpty())
                .flatMap(s -> replyMessage(event, s))
                .subscriberContext(Context.of(REQUEST_ID_KEY, Long.toHexString(RANDOM.nextLong())))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null,
                           t -> logger.error("Failed to handle event<{}>", event, t),
                           () -> logger.info("Replied to text<{}> in channel<{}>",
                                             event.getText(), event.getChannel()));
        }

        private Mono<Void> replyMessage(MessageEvent event, String text) {
            return Mono.fromRunnable(() -> {
                final long id = msgId.incrementAndGet();
                rtmClient.sendMessage(Message.builder()
                                             .id(id)
                                             .channel(event.getChannel())
                                             .text(text)
                                             .build()
                                             .toJSONString());

                logger.debug("Replied to text<{}> in channel<{}>;id<{}>",
                             event.getText(), event.getChannel(), id);
            });
        }
    }
}
