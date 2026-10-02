package com.puzzlesolver.spell.server

import com.puzzlesolver.spell.Lexicon
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.system.exitProcess

fun main() {
    val log = LoggerFactory.getLogger("spell-server")
    val config = ServerConfig.fromEnvironment()
    // Fail at start, loudly, rather than serve lobbies that never find a word.
    if (Files.isDirectory(config.dictionary)) {
        // The usual way to get here: the app was started before the word list was copied
        // onto the NAS, and Docker made a directory where the mounted file should have been.
        log.error(
            "{} is a directory, not the word list: Docker makes one when a mounted file is missing. " +
                "Remove the directory on the host, copy the word list in its place and restart (see docs/SPELLINATOR.md)",
            config.dictionary,
        )
        exitProcess(1)
    }
    if (!Files.isReadable(config.dictionary)) {
        log.error("no word list at {} -- mount it and set SPELL_DICTIONARY (see docs/SPELLINATOR.md)", config.dictionary)
        exitProcess(1)
    }
    val lexicon = Lexicon.load(config.dictionary)
    if (lexicon.size == 0) {
        log.error("{} has no usable words", config.dictionary)
        exitProcess(1)
    }
    log.info(
        "{} words from {}; port {}, trust X-Forwarded-For: {}, seats held {}s",
        lexicon.size, config.dictionary.fileName, config.port, config.trustForwarded, config.graceMillis / 1000,
    )
    val server = SpellServer(config, lexicon)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start(wait = true)
}
