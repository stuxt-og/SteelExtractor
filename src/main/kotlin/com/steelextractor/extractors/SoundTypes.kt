package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.SoundType
import java.lang.reflect.Modifier

class SoundTypes : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/sound_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        // Extract all public static final SoundType fields from SoundType class
        for (field in SoundType::class.java.declaredFields) {
            val modifiers = field.modifiers
            if (Modifier.isPublic(modifiers) &&
                Modifier.isStatic(modifiers) &&
                Modifier.isFinal(modifiers) &&
                field.type == SoundType::class.java
            ) {
                val name = field.name
                val soundType = field.get(null) as SoundType

                val typeJson = JsonObject()
                typeJson.addProperty("volume", soundType.volume)
                typeJson.addProperty("pitch", soundType.pitch)

                typeJson.addProperty("break_sound", getSoundEventKey(soundType.breakSound))
                typeJson.addProperty("step_sound", getSoundEventKey(soundType.stepSound))
                typeJson.addProperty("place_sound", getSoundEventKey(soundType.placeSound))
                typeJson.addProperty("hit_sound", getSoundEventKey(soundType.hitSound))
                typeJson.addProperty("fall_sound", getSoundEventKey(soundType.fallSound))

                json.add(name, typeJson)
            }
        }

        return json
    }

    private fun getSoundEventKey(soundEvent: net.minecraft.sounds.SoundEvent): String {
        val key = BuiltInRegistries.SOUND_EVENT.getKey(soundEvent)
            ?: error("Sound type references unregistered sound event: $soundEvent")
        return key.toString()
    }
}
