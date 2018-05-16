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

package fr.vsct.tock.shared

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinInjector
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.provider
import com.github.salomonbrys.kodein.providerOrNull
import com.github.salomonbrys.kodein.singleton
import com.mongodb.MongoClient
import fr.vsct.tock.shared.cache.TockCache
import fr.vsct.tock.shared.cache.mongo.MongoCache
import fr.vsct.tock.shared.vertx.TockVertxProvider
import fr.vsct.tock.shared.vertx.VertxProvider
import fr.vsct.tock.shared.vertx.vertxExecutor

/**
 * Internal injector - reset only for tests.
 */
var tockInternalInjector = KodeinInjector()

/**
 * Main Tock injector.
 */
val injector: KodeinInjector get() = tockInternalInjector

/**
 * Extension function for Ioc. Pattern:
 * <code>val core: NlpCore get() = injector.provide()</code>
 */
inline fun <reified T : Any> KodeinInjector.provide(tag: Any? = null): T =
    injector.provider<T>(tag).value.invoke()

/**
 * Extension function for Ioc. Pattern:
 * <code>val core: NlpCore get() = injector.provideOrDefault() { ... }</code>
 */
inline fun <reified T : Any> KodeinInjector.provideOrDefault(tag: Any? = null, defaultValueProvider: () -> T): T =
    try {
        injector.providerOrNull<T>(tag).value?.invoke() ?: defaultValueProvider.invoke()
    } catch (e: KodeinInjector.UninjectedException) {
        defaultValueProvider.invoke()
    }

/**
 * IOC of shared module.
 */
val sharedModule = Kodein.Module {
    bind<Executor>() with provider { vertxExecutor() }
    bind<TockCache>() with provider { MongoCache }
    bind<VertxProvider>() with provider { TockVertxProvider }
    bind<MongoClient>() with singleton { mongoClient }
}