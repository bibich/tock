/*
 * Copyright (C) 2017/2020 e-voyageurs technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.tock.bot.connector.web

import ai.tock.bot.connector.ConnectorBase
import ai.tock.bot.connector.ConnectorCallback
import ai.tock.bot.connector.ConnectorData
import ai.tock.bot.connector.ConnectorMessage
import ai.tock.bot.connector.ConnectorType
import ai.tock.bot.connector.media.MediaAction
import ai.tock.bot.connector.media.MediaCard
import ai.tock.bot.connector.media.MediaCarousel
import ai.tock.bot.connector.media.MediaMessage
import ai.tock.bot.connector.web.channel.ChannelMongoDAO
import ai.tock.bot.connector.web.channel.Channels
import ai.tock.bot.connector.web.send.PostbackButton
import ai.tock.bot.connector.web.send.UrlButton
import ai.tock.bot.connector.web.send.WebCard
import ai.tock.bot.connector.web.send.WebCarousel
import ai.tock.bot.engine.BotBus
import ai.tock.bot.engine.BotRepository
import ai.tock.bot.engine.ConnectorController
import ai.tock.bot.engine.action.Action
import ai.tock.bot.engine.event.Event
import ai.tock.bot.engine.user.PlayerId
import ai.tock.bot.engine.user.PlayerType.bot
import ai.tock.bot.engine.user.PlayerType.user
import ai.tock.bot.engine.user.UserPreferences
import ai.tock.bot.orchestration.bot.primary.orchestrationEnabled
import ai.tock.bot.orchestration.bot.secondary.OrchestrationCallback
import ai.tock.bot.orchestration.bot.secondary.RestOrchestrationCallback
import ai.tock.bot.orchestration.shared.AskEligibilityToOrchestratedBotRequest
import ai.tock.bot.orchestration.shared.OrchestrationMetaData
import ai.tock.bot.orchestration.shared.ResumeOrchestrationRequest
import ai.tock.bot.orchestration.shared.SecondaryBotEligibilityResponse
import ai.tock.shared.Dice
import ai.tock.shared.Executor
import ai.tock.shared.booleanProperty
import ai.tock.shared.injector
import ai.tock.shared.jackson.mapper
import ai.tock.shared.longProperty
import ai.tock.shared.provide
import ai.tock.shared.vertx.vertx
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.fasterxml.jackson.module.kotlin.readValue
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.CorsHandler
import mu.KotlinLogging
import java.time.Duration

internal const val WEB_CONNECTOR_ID = "web"

/**
 * The web (REST) connector type.
 */
val webConnectorType = ConnectorType(WEB_CONNECTOR_ID)

private val sseEnabled = booleanProperty("tock_web_sse", false)
private val sseKeepaliveDelay = longProperty("tock_web_sse_keepalive_delay", 10)

class WebConnector internal constructor(
    val applicationId: String,
    val path: String
) : ConnectorBase(webConnectorType) {

    companion object {
        private val logger = KotlinLogging.logger {}
        private val webMapper = mapper.copy().registerModules(
            SimpleModule().apply {
                //fallback for serializing CharSequence
                addSerializer(CharSequence::class.java, ToStringSerializer())
            }
        )
        private val channels by lazy { Channels(ChannelMongoDAO) }
    }

    private val executor: Executor get() = injector.provide()

    override fun register(controller: ConnectorController) {

        controller.registerServices(path) { router ->
            logger.debug("deploy web connector services for root path $path ")

            router.route(path)
                .handler(
                    CorsHandler.create("*")
                        .allowedMethod(HttpMethod.POST)
                        .run {
                            if (sseEnabled) allowedMethod(HttpMethod.GET) else this
                        }
                        .allowedHeader("Access-Control-Allow-Origin")
                        .allowedHeader("Content-Type")
                        .allowedHeader("X-Requested-With")
                )
            if (sseEnabled) {
                router.route(path + "/sse")
                    .handler { context ->
                        try {
                            val userId = context.queryParams()["userId"]
                            val response = context.response()
                            response.isChunked = true
                            response.headers().add("Content-Type", "text/event-stream;charset=UTF-8")
                            response.headers().add("Connection", "keep-alive")
                            response.headers().add("Cache-Control", "no-cache")
                            val timerId = vertx.setPeriodic(Duration.ofSeconds(sseKeepaliveDelay).toMillis()) {
                                response.write("event: ping\n")
                                response.write("data: 1\n\n")
                            }
                            val channelId = channels.register(applicationId, userId) { webConnectorResponse ->
                                response.write("event: message\n")
                                response.write("data: ${webMapper.writeValueAsString(webConnectorResponse)}\n\n")
                            }
                            response.closeHandler {
                                vertx.cancelTimer(timerId)
                                channels.unregister(channelId)
                            }
                        } catch (t: Throwable) {
                            context.fail(t)
                        }
                    }
            }
            router.post(path)
                .handler { context ->
                    try {
                        executor.executeBlocking {
                            handleRequest(controller, context, context.bodyAsString)
                        }
                    } catch (e: Throwable) {
                        context.fail(e)
                    }
                }

            if (orchestrationEnabled) {

                router.post("$path/orchestration/eligibility").handler { context ->
                    executor.executeBlocking {
                        handleEligibility(controller, context)
                    }
                }

                router.post("$path/orchestration/proxy").handler { context ->
                    executor.executeBlocking {
                        handleProxy(controller, context)
                    }
                }
            }
        }
    }

    private fun handleRequest(
        controller: ConnectorController,
        context: RoutingContext,
        body: String
    ) {
        val timerData = BotRepository.requestTimer.start("web_webhook")
        try {
            logger.debug { "Web request input : $body" }
            val request: WebConnectorRequest = mapper.readValue(body)
            val callback = WebConnectorCallback(
                applicationId = applicationId,
                locale = request.locale,
                context = context,
                webMapper = webMapper
            )
            controller.handle(request.toEvent(applicationId), ConnectorData(callback))
        } catch (t: Throwable) {
            BotRepository.requestTimer.throwable(t, timerData)
            context.fail(t)
        } finally {
            BotRepository.requestTimer.end(timerData)
        }
    }

    private fun handleProxy(
        controller: ConnectorController,
        context: RoutingContext
    ) {
        val timerData = BotRepository.requestTimer.start("web_webhook_orchestred")
        try {
            logger.debug { "Web proxy request input : ${context.bodyAsString}" }
            val request: ResumeOrchestrationRequest = mapper.readValue(context.bodyAsString)
            val callback = RestOrchestrationCallback(
                webConnectorType,
                applicationId = applicationId,
                context = context,
                orchestrationMapper = webMapper
            )

            controller.handle(request.toAction(), ConnectorData(callback))

        } catch (t: Throwable) {
            RestOrchestrationCallback(webConnectorType, applicationId, context = context).sendError()
            BotRepository.requestTimer.throwable(t, timerData)
        } finally {
            BotRepository.requestTimer.end(timerData)
        }
    }

    private fun handleEligibility(
        controller: ConnectorController,
        context: RoutingContext
    ) {
        val timerData = BotRepository.requestTimer.start("web_webhook_support")
        try {
            logger.debug { "Web support request input : ${context.bodyAsString}" }
            val request: AskEligibilityToOrchestratedBotRequest = mapper.readValue(context.bodyAsString)
            val callback = RestOrchestrationCallback(
                webConnectorType,
                applicationId,
                context = context,
                orchestrationMapper = webMapper
            )

            val support = controller.support(request.toAction(applicationId), ConnectorData(callback))
            val sendEligibility = SecondaryBotEligibilityResponse(
                support, OrchestrationMetaData(
                    playerId = PlayerId(applicationId, bot),
                    applicationId = applicationId,
                    recipientId = request.metadata?.playerId ?: PlayerId(Dice.newId(), user)
                )
            )
            callback.sendResponse(sendEligibility)

        } catch (t: Throwable) {
            RestOrchestrationCallback(webConnectorType, applicationId, context = context).sendError()
            BotRepository.requestTimer.throwable(t, timerData)
        } finally {
            BotRepository.requestTimer.end(timerData)
        }
    }

    override fun send(event: Event, callback: ConnectorCallback, delayInMs: Long) {
        if (event is Action) {
            when (callback) {
                is WebConnectorCallback -> handleWebConnectorCallback(callback, event)
                is OrchestrationCallback -> handleOrchestrationCallback(callback, event)
            }
        } else {
            logger.trace { "unsupported event: $event" }
        }

    }

    private fun handleWebConnectorCallback(callback: WebConnectorCallback, event: Action) {
        callback.addAction(event)
        if (sseEnabled) {
            channels.send(event)
        }
        if (event.metadata.lastAnswer) {
            callback.sendResponse()
        }
    }

    private fun handleOrchestrationCallback(callback: OrchestrationCallback, event: Action) {
        callback.actions.add(event)
        if (event.metadata.lastAnswer) {
            callback.sendResponse()
        }
    }

    override fun loadProfile(callback: ConnectorCallback, userId: PlayerId): UserPreferences {
        return when (callback) {
            is WebConnectorCallback -> UserPreferences().apply { locale = callback.locale }
            else -> UserPreferences()
        }
    }

    override fun addSuggestions(text: CharSequence, suggestions: List<CharSequence>): BotBus.() -> ConnectorMessage? =
        { WebMessage(text.toString(), suggestions.map { webPostbackButton(it) }) }

    override fun addSuggestions(
        message: ConnectorMessage,
        suggestions: List<CharSequence>
    ): BotBus.() -> ConnectorMessage? = {
        (message as? WebMessage)?.let {
            if (it.card != null && it.card.buttons.isEmpty()) {
                it.copy(card = it.card.copy(buttons = suggestions.map { s -> webPostbackButton(s) }))
            } else if (it.card == null && it.buttons.isEmpty()) {
                it.copy(buttons = suggestions.map { s -> webPostbackButton(s) })
            } else {
                null
            }
        } ?: message
    }

    override fun toConnectorMessage(message: MediaMessage): BotBus.() -> List<ConnectorMessage> = {
        listOfNotNull(
            when (message) {
                is MediaCard -> {
                    WebMessage(
                        card = WebCard(
                            title = message.title,
                            subTitle = message.subTitle,
                            file = message.file,
                            buttons = message.actions.map { button -> button.toButton() }
                        ))
                }
                is MediaCarousel -> {
                    WebMessage(carousel = WebCarousel(message.cards.map { mediaCard ->
                        WebCard(
                            title = mediaCard.title,
                            subTitle = mediaCard.subTitle,
                            file = mediaCard.file,
                            buttons = mediaCard.actions.map { button -> button.toButton() }
                        )
                    }))
                }
                else -> null
            }
        )
    }

    private fun MediaAction.toButton() =
        if (url == null) {
            PostbackButton(title.toString(), null)
        } else {
            UrlButton(title.toString(), url.toString())
        }
}