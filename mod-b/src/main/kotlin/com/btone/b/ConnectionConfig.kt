package com.btone.b

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ConnectionConfig(val port: Int, val token: String, val version: String) {
    fun writeTo(path: Path) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "btone-", ".tmp")
        Files.writeString(tmp, mapper.writeValueAsString(this))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val mapper: ObjectMapper = jacksonObjectMapper()
        fun readFrom(path: Path): ConnectionConfig =
            mapper.readValue(Files.readString(path).toByteArray(), ConnectionConfig::class.java)
    }
}
