package com.resyst.vk.core

/**
 * r10 (F-3, V1–V4): offensive words the keyboard never PROPOSES while "Filtrar palabras
 * ofensivas" is on — completions, predictions, seeds and the space correction. It never edits or
 * blocks what the user types, never touches the personal model, and a word the user has typed
 * [Bar.HABIT] times is theirs again (the bar checks that, not this list).
 *
 * Whole words only, compared case- and accent-insensitively ([Suggest.fold]): "computadora",
 * "escultura", "shitake" and "assistant" are clean. One union list for both languages — people
 * who write in two languages swear in both. Mild words (idiota, damn, crap…) and words with an
 * everyday meaning somewhere in the Spanish-speaking world (coger, polla, concha, pito) are left
 * alone on purpose: the filter is for words nobody wants a keyboard to suggest. The ranks in the
 * comments are the shipped subtitle lexicons' (r8b), where these words sit very high.
 */
object Profanity {

    private val ES = """
        mierda mierdas mierdoso mierdosa
        puta putas puto putos putita putito putitas putitos putada putadas putear puteando puteo hijoputa hijoputas hijueputa hijueputas
        culo culos culiao culiaos culiado culiados culiá culiao
        coño coños joder jodido jodida jodidos jodidas jódete jodete jodiendo jodí jodió
        cabrón cabrona cabrones cabronas cabronazo
        gilipollas gilipolla gilipuertas
        pendejo pendeja pendejos pendejas pendejada pendejadas
        verga vergas vergazo chingada chingado chingados chingar chinga chingas chingue chingón chingona pinche pinches
        maricón maricones marica maricas mariconazo mariposón bollera bolleras tortillera tortilleras
        conchetumare conchetumadre conchesumadre conchasumadre ctm csm
        chucha chuchas weón weona weones weonas huevón huevona huevones huevonas aweonao aweonado
        follar follando follamos follado follada folla follas follen
        cojones cojón cojonudo
        zorra zorras zorrita puñeta puñetas pajero pajera pajeros pajeras pajote
        mamón mamona mamones mamonas mamada mamadas malparido malparida malparidos malparidas gonorrea
        negrata negratas sudaca sudacas retrasado retrasada subnormal subnormales mongólico mongólica
        carajo carajos ojete ojetes culero culera culeros
    """

    private val EN = """
        fuck fucks fucked fucker fuckers fucking fuckin fuckhead fuckface fuckoff motherfucker motherfuckers motherfucking
        shit shits shitty shitting shithead shitheads bullshit horseshit dipshit
        ass asses asshole assholes arse arsehole dumbass jackass smartass badass
        bitch bitches bitching bitchy son-of-a-bitch
        bastard bastards dick dicks dickhead dickheads cock cocks cocksucker cocksuckers
        cunt cunts pussy pussies twat twats wanker wankers prick pricks
        whore whores slut sluts skank hoe hoes
        fag fags faggot faggots dyke dykes tranny nigger niggers nigga niggas retard retards retarded spic chink kike
        cum jizz goddamn goddamned piss pissed pissing
    """

    private val WORDS: Set<String> = (ES + " " + EN).trim().split(Regex("\\s+")).map { Suggest.fold(it) }.toSet()

    /** Whether the keyboard must not propose [word] (any case, with or without accents). */
    @Suppress("UNUSED_PARAMETER")
    fun blocked(word: String, lang: Lang): Boolean = word.isNotEmpty() && Suggest.fold(word) in WORDS

    /** How many entries the list holds (diagnostics / tests). */
    val size: Int get() = WORDS.size
}
