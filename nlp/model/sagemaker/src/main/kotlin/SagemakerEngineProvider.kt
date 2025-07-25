/*
 * Copyright (C) 2017/2021 e-voyageurs technologies
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

package ai.tock.nlp.sagemaker

import NlpHealthcheckResult
import ai.tock.nlp.core.NlpEngineType
import ai.tock.nlp.model.TokenizerContext
import ai.tock.nlp.model.service.engine.EntityClassifier
import ai.tock.nlp.model.service.engine.EntityModelHolder
import ai.tock.nlp.model.service.engine.IntentClassifier
import ai.tock.nlp.model.service.engine.IntentModelHolder
import ai.tock.nlp.model.service.engine.NlpEngineModelBuilder
import ai.tock.nlp.model.service.engine.NlpEngineModelIo
import ai.tock.nlp.model.service.engine.NlpEngineProvider
import ai.tock.nlp.model.service.engine.Tokenizer
import ai.tock.nlp.model.service.engine.TokenizerModelHolder

class SagemakerEngineProvider : NlpEngineProvider {

    private val threadLocal = ThreadLocal<SagemakerIntentClassifier>()

    private fun getSagemakerClassifier(conf: SagemakerModelConfiguration? = null, new: Boolean): SagemakerIntentClassifier =
        if (new) {
            threadLocal.remove()
            SagemakerIntentClassifier(conf ?: error("no sagemaker configuration")).apply { threadLocal.set(this) }
        } else {
            threadLocal.get().also { threadLocal.remove() } ?: error("no sagemaker classifier found")
        }

    // Default sagemaker model
    override val type: NlpEngineType = NlpEngineType.bgem3

    override val modelBuilder: NlpEngineModelBuilder = SagemakerNlpModelBuilder

    override val modelIo: NlpEngineModelIo = SagemakerNlpModelIo

    override fun getIntentClassifier(model: IntentModelHolder): IntentClassifier =
        getSagemakerClassifier(model.nativeModel as SagemakerModelConfiguration, true)

    override fun getEntityClassifier(model: EntityModelHolder): EntityClassifier = SagemakerEntityClassifier(model)

    override fun getTokenizer(model: TokenizerModelHolder): Tokenizer = object : Tokenizer {
        // do not tokenize anything at this stage
        override fun tokenize(context: TokenizerContext, text: String): Array<String> = arrayOf(text)
    }

    override fun healthcheck(): () -> NlpHealthcheckResult = {
        val clients = SagemakerClientProvider.getAllClient()
        if (clients.isEmpty()) {
            NlpHealthcheckResult(
                entityClassifier = false,
                intentClassifier = false
            )
        } else {
            val grouped = clients.groupBy { it.name }.withDefault { emptyList() }
            val entityClients = grouped.getValue(SagemakerEntityClassifier.CLIENT_TYPE.clientName)
            val intentClients = grouped.getValue(SagemakerIntentClassifier.CLIENT_TYPE.clientName)

            NlpHealthcheckResult(
                entityClassifier = entityClients.isNotEmpty() && entityClients.all { it.healthcheck() },
                intentClassifier = intentClients.isNotEmpty() && intentClients.all { it.healthcheck() }
            )
        }
    }
}

