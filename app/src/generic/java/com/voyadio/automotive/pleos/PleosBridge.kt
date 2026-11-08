package com.voyadio.automotive.pleos

import android.content.Context

/** The 'generic' build has no Pleos SDK (42dot licence §3.2: the SDK only in apps for Pleos Connect). */
object PleosBridge {
    val permissions = emptyList<String>()

    fun connect(context: Context): PleosConnect? = null
}
