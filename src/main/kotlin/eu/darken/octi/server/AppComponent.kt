package eu.darken.octi.server

import dagger.BindsInstance
import dagger.Component
import eu.darken.octi.server.common.serialization.SerializationModule
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        SerializationModule::class
    ]
)
interface AppComponent {
    fun application(): App

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun config(config: App.Config): Builder

        fun build(): AppComponent
    }
}