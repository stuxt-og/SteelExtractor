package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

class SoundEvents : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/sound_events.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonArray()

        for (soundEvent in BuiltInRegistries.SOUND_EVENT) {
            val key = BuiltInRegistries.SOUND_EVENT.getKey(soundEvent)
                ?: error("Sound event has no registry key: $soundEvent")
            val id = BuiltInRegistries.SOUND_EVENT.getId(soundEvent)

            val entry = JsonObject()
            entry.addProperty("id", id)
            entry.addProperty("key", key.toString())
            entry.addProperty("sound_id", soundEvent.location().toString())
            soundEvent.fixedRange().ifPresent { range ->
                entry.addProperty("fixed_range", range)
            }
            json.add(entry)
        }

        return json
    }
}
