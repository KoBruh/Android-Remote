package com.androidremote.app

import android.app.Application
import android.content.Context

class RemoteApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** The process-wide [RemoteController], for activities, services, tiles and widgets alike. */
val Context.remoteController: RemoteController
    get() = (applicationContext as RemoteApp).container.controller
