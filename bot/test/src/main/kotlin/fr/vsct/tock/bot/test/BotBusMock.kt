/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.tock.bot.test

import fr.vsct.tock.bot.connector.ConnectorCallbackBase
import fr.vsct.tock.bot.connector.ConnectorData
import fr.vsct.tock.bot.connector.ConnectorMessage
import fr.vsct.tock.bot.connector.ConnectorType
import fr.vsct.tock.bot.definition.BotDefinition
import fr.vsct.tock.bot.definition.IntentAware
import fr.vsct.tock.bot.engine.BotBus
import fr.vsct.tock.bot.engine.action.Action
import fr.vsct.tock.bot.engine.action.ActionNotificationType
import fr.vsct.tock.bot.engine.action.ActionPriority
import fr.vsct.tock.bot.engine.action.SendChoice
import fr.vsct.tock.bot.engine.action.SendSentence
import fr.vsct.tock.bot.engine.dialog.ContextValue
import fr.vsct.tock.bot.engine.dialog.Dialog
import fr.vsct.tock.bot.engine.dialog.EntityStateValue
import fr.vsct.tock.bot.engine.dialog.NextUserActionState
import fr.vsct.tock.bot.engine.dialog.Snapshot
import fr.vsct.tock.bot.engine.dialog.Story
import fr.vsct.tock.bot.engine.user.UserPreferences
import fr.vsct.tock.bot.engine.user.UserTimeline
import fr.vsct.tock.nlp.api.client.model.Entity
import fr.vsct.tock.nlp.entity.Value
import fr.vsct.tock.shared.defaultLocale
import fr.vsct.tock.shared.provide
import fr.vsct.tock.translator.I18nKeyProvider
import fr.vsct.tock.translator.I18nLabelKey
import fr.vsct.tock.translator.Translator
import fr.vsct.tock.translator.TranslatorEngine
import fr.vsct.tock.translator.UserInterfaceType
import mu.KotlinLogging
import java.util.Locale

/**
 * A Bus mock used in unit tests.
 *
 * The answers of the bot are available in the [answers] property.
 */
open class BotBusMock(val context: BotBusMockContext,
                      override val action: Action = context.firstAction) : BotBus {

    private val logger = KotlinLogging.logger {}

    private val logsRepository: List<BotBusMockLog> = mutableListOf()

    /**
     * The list of all bot answers recorded.
     */
    val answers: List<BotBusMockLog> get() = checkEndCalled().run { context.answers }

    /**
     * The first answer recorded.
     */
    val firstAnswer: BotBusMockLog get() = checkEndCalled().run { context.firstAnswer }

    /**
     * The second answer recorded.
     */
    val secondAnswer: BotBusMockLog get() = checkEndCalled().run { context.secondAnswer }

    /**
     * The third answer recorded.
     */
    val thirdAnswer: BotBusMockLog get() = checkEndCalled().run { context.thirdAnswer }

    /**
     * The last answer recorded.
     */
    val lastAnswer: BotBusMockLog get() = checkEndCalled().run { context.lastAnswer }

    /**
     * The list of bot answers for this bus.
     */
    val busAnswers: List<BotBusMockLog> get() = checkEndCalled().run { logsRepository }

    /**
     * The first answer for this bus.
     */
    val firstBusAnswer: BotBusMockLog get() = checkEndCalled().run { logsRepository.first() }

    /**
     * The second answer for this bus.
     */
    val secondBusAnswer: BotBusMockLog get() = checkEndCalled().run { logsRepository[1] }

    /**
     * The third answer for this bus.
     */
    val thirdBusAnswer: BotBusMockLog get() = checkEndCalled().run { logsRepository[2] }

    /**
     * The last answer for this bus.
     */
    val lastBusAnswer: BotBusMockLog get() = checkEndCalled().run { logsRepository.last() }

    private var endCalled: Boolean = false

    /**
     * Run the [StoryHandler] of the current [story].
     */
    fun run(): BotBusMock {
        story.definition.storyHandler.handle(this)
        return this
    }

    /**
     * Throws an exception if the end() is not called
     */
    fun checkEndCalled(): BotBusMock {
        if (!endCalled) error("end() method not called")
        return this
    }

    /**
     * Add an entity set in the current action.
     */
    fun addActionEntity(contextValue: ContextValue): BotBusMock {
        action.state.entityValues.add(contextValue)
        return this
    }

    /**
     * Add an entity set in the current action.
     */
    fun addActionEntity(entity: Entity, newValue: Value?): BotBusMock = addActionEntity(ContextValue(entity, newValue))

    /**
     * Simulate an action entity.
     */
    fun addActionEntity(entity: Entity, textContent: String): BotBusMock = addActionEntity(ContextValue(entity, null, textContent))

    override var userTimeline: UserTimeline
        get() = context.userTimeline
        set(value) {
            context.userTimeline = value
        }
    override var dialog: Dialog
        get() = context.dialog
        set(value) {
            context.dialog = value
        }
    override var story: Story
        get() = context.story
        set(value) {
            context.story = value
        }
    override var botDefinition: BotDefinition
        get() = context.botDefinition
        set(value) {
            context.botDefinition = value
        }
    override var i18nProvider: I18nKeyProvider
        get() = context.i18nProvider
        set(value) {
            context.i18nProvider = value
        }
    override var userInterfaceType: UserInterfaceType
        get() = context.userInterfaceType
        set(value) {
            context.userInterfaceType = value
        }
    var connectorType: ConnectorType
        get() = context.connectorType
        set(value) {
            context.connectorType = value
        }

    override var connectorData: ConnectorData = ConnectorData(ConnectorCallbackBase(action.applicationId, connectorType))
    /**
     * The translator used to translate labels - default is NoOp.
     */
    val translator: TranslatorEngine get() = context.testContext.testInjector.provide()
    override val applicationId get() = action.applicationId
    override val botId get() = action.recipientId
    override val userId get() = action.playerId
    override val userPreferences: UserPreferences get() = userTimeline.userPreferences
    override val userLocale: Locale get() = userPreferences.locale
    override var targetConnectorType: ConnectorType
        get() = action.state.targetConnectorType ?: connectorType
        set(value) {
            action.state.targetConnectorType = value
            connectorType = value
        }

    private val mockData: BusMockData = BusMockData()

    override val entities: Map<String, EntityStateValue>
        get() = dialog.state.entityValues

    override var intent: IntentAware?
        get() = dialog.state.currentIntent
        set(value) {
            dialog.state.currentIntent = value?.wrappedIntent()
        }

    override var nextUserActionState: NextUserActionState?
        get() = dialog.state.nextActionState
        set(value) {
            dialog.state.nextActionState = value
        }

    init {
        val a = action
        if (a is SendChoice) {
            context.dialog.state.currentIntent = context.botDefinition.findIntent(a.intentName)
            context.story.apply {
                if (a.step() != null) {
                    currentStep = a.step()
                }
            }
        }
        if (a.state.intent != null) {
            context.dialog.state.currentIntent = context.botDefinition.findIntent(a.state.intent!!)
        }
        a.state.entityValues.forEach {
            dialog.state.changeValue(it)
        }

        if (dialog.stories.isEmpty()) {
            dialog.stories.add(story)
        }
        if (a != context.firstAction) {
            story.actions.add(a)
        }
        if (a.state.userInterface != null) {
            context.userInterfaceType = a.state.userInterface!!
        }
    }


    open fun sendAction(action: Action, delay: Long) {
        (logsRepository as MutableList).add(BotBusMockLog(action, delay))
        (context.logsRepository as MutableList).add(BotBusMockLog(action, delay))
    }

    private fun answer(action: Action, delay: Long = 0): BotBus {
        mockData.currentDelay += delay
        action.metadata.priority = mockData.priority
        if (action is SendSentence) {
            action.messages.addAll(mockData.connectorMessages.values)
        }
        mockData.clear()
        action.state.testEvent = userPreferences.test

        story.actions.add(action)

        endCalled = action.metadata.lastAnswer

        if (endCalled) {
            addSnapshot()
        }

        sendAction(action, mockData.currentDelay)
        return this
    }

    private fun addSnapshot() {
        context.snapshots.add(Snapshot(dialog.state.entityValues.values.mapNotNull { it.value }))
    }

    /**
     * Returns the non persistent current value.
     */
    override fun getBusContextValue(name: String): Any? {
        return mockData.contextMap[name]
    }

    /**
     * Update the non persistent current value.
     */
    override fun setBusContextValue(key: String, value: Any?) {
        if (value == null) {
            mockData.contextMap - key
        } else {
            mockData.contextMap.put(key, value)
        }
    }

    override fun end(action: Action, delay: Long): BotBus {
        action.metadata.lastAnswer = true
        return answer(action, delay)
    }

    override fun sendRawText(plainText: CharSequence?, delay: Long): BotBus {
        return answer(SendSentence(botId, applicationId, userId, plainText), delay)
    }

    override fun send(action: Action, delay: Long): BotBus {
        return answer(action, delay)
    }

    override fun withPriority(priority: ActionPriority): BotBus {
        mockData.priority = priority
        return this
    }

    override fun withNotificationType(notificationType: ActionNotificationType): BotBus {
        mockData.notificationType = notificationType
        return this
    }

    override fun withMessage(connectorType: ConnectorType, messageProvider: () -> ConnectorMessage): BotBus {
        if (targetConnectorType == connectorType) {
            mockData.addMessage(messageProvider.invoke())
        }
        return this
    }

    override fun reloadProfile() {
        userPreferences.fillWith(context.initialUserPreferences)
    }

    override fun translate(key: I18nLabelKey?): CharSequence =
            if (key == null) ""
            else Translator.formatMessage(
                    translator.translate(
                            key.defaultLabel.toString(),
                            defaultLocale,
                            userTimeline.userPreferences.locale
                    ),
                    userTimeline.userPreferences.locale,
                    userInterfaceType,
                    targetConnectorType.id,
                    key.args)
}