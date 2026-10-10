package com.resyst.vk.core

/**
 * r11b: the words the GIF search shows, in one place so the keyboard's disclosure, the settings
 * dialog and the error lines say exactly the same thing (GO2, GE1, GT1).
 */
object GifCopy {
    const val TITLE = "Búsqueda de GIF con KLIPY"
    const val SETTING = "Búsqueda de GIF (KLIPY)"
    const val PLACEHOLDER = "Buscar en KLIPY"
    const val ATTRIBUTION = "Powered by KLIPY"

    /** The OFF explainer in the GIF tab. */
    const val OFF_LINE1 = "Busca y envía GIF sin salir del teclado."
    const val OFF_LINE2 = "Está apagado: hasta que lo actives, el teclado no se conecta a nadie."
    const val OFF_BUTTON = "Ver qué se envía y activar"

    /** GO2: what is sent, to whom, what never is, where it is recorded, how to turn it off. */
    val DISCLOSURE: List<String> = listOf(
        "Solo se envía lo que escribas en el buscador de GIF, un ID anónimo al azar, tu país y el nivel de filtro. Lo que escribes en las apps nunca se envía.",
        "Va a KLIPY, un servicio de otra empresa (no de Resyst). Los GIF se cargan directo desde KLIPY, que ve tu IP como en cualquier conexión. Al enviar un GIF se le avisa cuál fue.",
        "Cada conexión queda anotada en Ajustes › Acerca de › Libro de conexiones.",
        "Apágalo cuando quieras en Ajustes › Portapapeles y privacidad: se borra el ID y no se conecta más.",
    )
    const val ACCEPT = "Activar"
    const val CANCEL = "Cancelar"

    const val LOADING = "Cargando…"
    const val EMPTY = "Sin resultados en KLIPY"
    const val NOT_ACCEPTED = "Este campo no acepta GIF"
    const val TOO_BIG = "Este GIF es muy grande para enviarlo aquí"

    /** One calm line per failure (GE1); the user retries by tapping, never automatically. */
    enum class Fail(val line: String, val log: String) {
        OFFLINE("Sin conexión", "Sin conexión"),
        TIMEOUT("KLIPY no respondió a tiempo", "Tiempo agotado"),
        RATE_LIMIT("Demasiadas búsquedas, intenta en un rato", "Límite de KLIPY (HTTP 429)"),
        HTTP("KLIPY respondió con un error", "Error HTTP"),
        REDIRECT("KLIPY respondió con un error", "Redirección rechazada"),
        BAD_RESPONSE("Respuesta ilegible de KLIPY", "Respuesta ilegible"),
        TOO_LARGE("Respuesta demasiado grande", "Respuesta demasiado grande"),
        TLS("No se pudo establecer una conexión segura", "Conexión no segura"),
        /** The user left the tab / the field while it was in flight: logged, never shown. */
        CANCELLED("", "Cancelada al salir"),
        ;

        companion object {
            /** HTTP status → failure (null = 200). 3xx is a refused redirect (GA2). */
            fun ofStatus(code: Int): Fail? = when {
                code == 200 -> null
                code == 429 -> RATE_LIMIT
                code in 300..399 -> REDIRECT
                else -> HTTP
            }
        }
    }

    /** A page entry's outcome in the book: what was asked and what came back. */
    fun pageOutcome(query: String?, page: Int, count: Int, fail: Fail?, status: Int = 0): String {
        val what = (if (query != null) "«$query»" else "tendencias") + if (page > 1) " · pág. $page" else ""
        val res = when {
            fail == Fail.HTTP && status > 0 -> "Error HTTP $status"
            fail != null -> fail.log
            count == 1 -> "1 GIF"
            else -> "$count GIF"
        }
        return "$what · $res"
    }
}
