package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Registry
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.entity.npc.villager.VillagerType

private fun <T : Any> extractBuiltInRegistry(
    registry: Registry<T>,
    addFields: (T, JsonObject) -> Unit = { _, _ -> }
): JsonArray {
    val values = JsonArray()
    for (entry in registry) {
        val key = registry.getKey(entry) ?: error("Built-in registry entry has no key: $entry")
        val entryJson = JsonObject()
        entryJson.addProperty("id", registry.getId(entry))
        entryJson.addProperty("key", key.toString())
        addFields(entry, entryJson)
        values.add(entryJson)
    }
    return values
}

class ParticleTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/particle_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.PARTICLE_TYPE) { particleType: ParticleType<*>, json ->
            json.addProperty("override_limiter", particleType.overrideLimiter)
        }
    }
}

class VillagerTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/villager_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.VILLAGER_TYPE) { _: VillagerType, _ -> }
    }
}

class VillagerProfessionRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/villager_professions.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.VILLAGER_PROFESSION) { profession: VillagerProfession, json ->
            val workSound = profession.workSound()
            if (workSound != null) {
                json.addProperty("work_sound", soundKey(workSound))
            }
        }
    }

    private fun soundKey(sound: SoundEvent): String {
        val key = BuiltInRegistries.SOUND_EVENT.getKey(sound)
            ?: error("Villager profession work sound has no key: $sound")
        return key.toString()
    }
}
