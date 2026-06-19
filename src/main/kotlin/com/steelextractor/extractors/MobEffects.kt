package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

class MobEffects : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-mob-effects")
    private val attributeModifiersField: Field = net.minecraft.world.effect.MobEffect::class.java
        .getDeclaredField("attributeModifiers")
        .apply { isAccessible = true }
    private val attributeTemplateClass = Class.forName("net.minecraft.world.effect.MobEffect\$AttributeTemplate")
    private val attributeTemplateIdField: Field = attributeTemplateClass
        .getDeclaredField("id")
        .apply { isAccessible = true }
    private val attributeTemplateAmountField: Field = attributeTemplateClass
        .getDeclaredField("amount")
        .apply { isAccessible = true }
    private val attributeTemplateOperationField: Field = attributeTemplateClass
        .getDeclaredField("operation")
        .apply { isAccessible = true }

    override fun fileName(): String {
        return "steel-registry/build_assets/mob_effects.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val effectsArray = JsonArray()

        for (effect in BuiltInRegistries.MOB_EFFECT) {
            val key = BuiltInRegistries.MOB_EFFECT.getKey(effect)
            val name = key?.path ?: "unknown"

            val effectJson = JsonObject()
            val id = BuiltInRegistries.MOB_EFFECT.getId(effect)

            effectJson.addProperty("id", id)
            effectJson.addProperty("name", name)

            try {
                effectJson.addProperty("category", effect.category.name)
                effectJson.addProperty("color", effect.color)
                val attributeModifiers = extractAttributeModifiers(effect)
                if (attributeModifiers.size() > 0) {
                    effectJson.add("attribute_modifiers", attributeModifiers)
                }
            } catch (e: Exception) {
                logger.warn("Failed to get info for " + name + ": " + e.message)
            }

            effectsArray.add(effectJson)
        }

        return effectsArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractAttributeModifiers(effect: net.minecraft.world.effect.MobEffect): JsonArray {
        val modifiersJson = JsonArray()
        val modifiers = attributeModifiersField.get(effect) as Map<Holder<Attribute>, Any>

        for ((attributeHolder, template) in modifiers.entries) {
            val attribute = attributeHolder.value()
            val attributeKey = BuiltInRegistries.ATTRIBUTE.getKey(attribute) ?: continue
            val modifierId = attributeTemplateIdField.get(template)
            val amount = attributeTemplateAmountField.get(template) as Double
            val operation = attributeTemplateOperationField.get(template) as AttributeModifier.Operation

            val modifierJson = JsonObject()
            modifierJson.addProperty("attribute", attributeKey.path)
            modifierJson.addProperty("id", modifierId.toString().removePrefix("minecraft:"))
            modifierJson.addProperty("amount", amount)
            modifierJson.addProperty("operation", operation.name)
            modifiersJson.add(modifierJson)
        }

        return modifiersJson
    }
}
