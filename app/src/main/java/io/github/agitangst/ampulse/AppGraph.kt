package io.github.agitangst.ampulse

import android.app.Application
import android.content.Context
import io.github.agitangst.ampulse.data.AmpulseDb
import io.github.agitangst.ampulse.data.ChangeListener
import io.github.agitangst.ampulse.data.Repository
import io.github.agitangst.ampulse.reminders.Reminders

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
        repository = Repository(AmpulseDb.open(app), ChangeListener { reminders.reschedule() })
        reminders.reschedule()
    }
}

class AmpulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
