package com.resyst.vk.ime

import android.content.Context
import com.resyst.vk.core.Txt
import com.resyst.vk.R

/** [Txt] over the device's resources: reads the current configuration on every call (TR7). */
class ResTxt(private val context: Context) : Txt {
    private val res get() = context.resources

    override fun get(id: Int, vararg args: Any): String = res.getString(id, *args)

    override fun plural(id: Int, n: Int, vararg args: Any): String = res.getQuantityString(id, n, n, *args)

    override fun named(name: String, vararg args: Any): String? {
        val id = res.getIdentifier(name, "string", context.packageName)
        return if (id == 0) null else res.getString(id, *args)
    }

    /** The language the resources resolved to (a phone in a third language gets the Spanish default). */
    override val lang: String get() = res.getString(R.string.lang_code)
}
