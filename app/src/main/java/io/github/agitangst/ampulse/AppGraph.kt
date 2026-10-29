package io.github.agitangst.ampulse

import android.app.Application
import android.content.Context
import io.github.agitangst.ampulse.data.AmpulseDb
import io.github.agitangst.ampulse.data.ChangeListener
import io.github.agitangst.ampulse.data.Repository
import io.github.agitangst.ampulse.reminders.Reminders
import io.github.agitangst.ampulse.sync.ServerSync
import io.github.agitangst.ampulse.sync.ServerWatch
import io.github.agitangst.ampulse.ui.CurrentProfile
import io.github.agitangst.ampulse.ui.EmergencyNotice

/** Общие объекты приложения: база, данные, напоминания. Создаются один раз в [AmpulseApp]. */
object AppGraph {
    lateinit var repository: Repository
        private set
    lateinit var reminders: Reminders
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        val app = context.applicationContext
        reminders = Reminders(app)
        ServerSync.init(app)
        ServerWatch.init(app)
        // Правка данных пересчитывает напоминания и (если вошли на свой сервер, сборка github) ставит отложенную выгрузку.
        repository = Repository(AmpulseDb.open(app), ChangeListener { reminders.reschedule(); ServerSync.onChange() })
        reminders.reschedule()
        CurrentProfile.load(app)
        EmergencyNotice.sync(app)
    }
}

class AmpulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
