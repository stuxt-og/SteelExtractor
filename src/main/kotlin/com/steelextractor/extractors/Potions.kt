package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.effect.MobEffectInstance
import org.slf4j.LoggerFactory

class Potions : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-potions")

    override fun fileName(): String {
        return "steel-registry/build_assets/potions.json"
    }

    fun exportInstance(effectInstance: MobEffectInstance): JsonObject {
        val effectJson = JsonObject()
        val effectKey = BuiltInRegistries.MOB_EFFECT.getKey(effectInstance.effect.value())
        effectJson.addProperty("name", effectKey?.path ?: "unknown")
        effectJson.addProperty("duration", effectInstance.duration)
        effectJson.addProperty("amplifier", effectInstance.amplifier)
        effectJson.addProperty("ambient", effectInstance.isAmbient)
        effectJson.addProperty("visible", effectInstance.isVisible)
        effectJson.addProperty("show_icon", effectInstance.showIcon())
        effectInstance.hiddenEffect?.let { effectJson.add("hidden_effect", exportInstance(it)) }

        return effectJson
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val potionsArray = JsonArray()

        for (potion in BuiltInRegistries.POTION) {
            val key = BuiltInRegistries.POTION.getKey(potion)
            val name = key?.path ?: "unknown"

            val potionJson = JsonObject()
            val id = BuiltInRegistries.POTION.getId(potion)

            potionJson.addProperty("id", id)
            potionJson.addProperty("name", name)

            try {
                // Extract effects
                val effectsArray = JsonArray()
                for (effectInstance in potion.effects) {
                    effectsArray.add(exportInstance(effectInstance))
                }
                potionJson.add("effects", effectsArray)
            } catch (e: Exception) {
                logger.warn("Failed to get info for " + name + ": " + e.message)
            }

            potionsArray.add(potionJson)
        }

        return potionsArray
    }
}
