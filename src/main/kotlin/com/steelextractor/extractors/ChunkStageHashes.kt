package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.ChunkStageHashStorage
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class ChunkStageHashes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-chunk-stage-hashes")

    override fun fileName(): String {
        return "steel-core/test_assets/chunk_stage_hashes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        val worldSeed = server.overworld().seed
        json.addProperty("seed", worldSeed)
        json.addProperty("chunk_sample_seed", SteelExtractor.CHUNK_SAMPLE_SEED)
        json.addProperty("num_chunks", SteelExtractor.NUM_SAMPLE_CHUNKS)
        json.addProperty("chunk_generation_order", "x_z_ascending")
        json.addProperty("feature_hash_capture", "after_all_tracked_features_ready")
        json.addProperty("biome_tie_breaker", "vanilla_rtree_chunk_local_cache")

        if (worldSeed != 13579L) {
            logger.warn("World seed is $worldSeed, not 13579! Set level-seed=13579 in server.properties and delete the world folder.")
        }

        val allHashes = ChunkStageHashStorage.getAllHashes()

        val dimensionsJson = JsonObject()
        val dimGroups = allHashes.entries.groupBy { it.key.first.dimension }

        for ((dimension, dimEntries) in dimGroups.toSortedMap()) {
            val dimJson = JsonObject()
            val chunkGroups = dimEntries.groupBy { it.key.first.pos }

            val chunksArray = JsonArray()
            for ((pos, entries) in chunkGroups.toSortedMap(compareBy({ it.x }, { it.z }))) {
                val chunkJson = JsonObject()
                chunkJson.addProperty("x", pos.x)
                chunkJson.addProperty("z", pos.z)

                val stagesJson = JsonObject()
                for ((key, hash) in entries.sortedBy { it.key.second }) {
                    val stageName = key.second
                    stagesJson.addProperty(stageName, hash)
                }
                chunkJson.add("stages", stagesJson)

                chunksArray.add(chunkJson)
            }

            dimJson.add("chunks", chunksArray)
            dimJson.addProperty("chunk_count", chunkGroups.size)
            dimensionsJson.add(dimension, dimJson)

            logger.info("Extracted chunk stage hashes for ${chunkGroups.size} chunks in $dimension")
        }

        json.add("dimensions", dimensionsJson)
        return json
    }

    fun captureFinalFeatureHashes(
        server: MinecraftServer,
        dimension: String,
        positions: Collection<ChunkPos>,
        featureChunks: Map<ChunkPos, ChunkAccess>
    ) {
        val positionSet = positions.toSet()
        val trackedChunks = ChunkStageHashStorage.getTrackedChunks()
            .filter { it.dimension == dimension && positionSet.contains(it.pos) }
            .sortedWith(compareBy({ it.pos.x }, { it.pos.z }))
        if (trackedChunks.isEmpty()) {
            return
        }

        val levelKey = when (dimension) {
            "minecraft:overworld" -> Level.OVERWORLD
            "minecraft:the_nether" -> Level.NETHER
            "minecraft:the_end" -> Level.END
            else -> {
                logger.warn("Cannot capture final feature hashes for unknown dimension $dimension")
                return
            }
        }
        val level = server.getLevel(levelKey) ?: run {
            logger.warn("Cannot capture final feature hashes for missing level $dimension")
            return
        }

        var captured = 0
        for (tracked in trackedChunks) {
            val chunk = featureChunks[tracked.pos] ?: level.getChunk(tracked.pos.x, tracked.pos.z, ChunkStatus.FEATURES, false)
            if (chunk == null) {
                logger.warn("Tracked chunk ${tracked.pos} in $dimension is not loaded at FEATURES; skipping final feature hash")
                continue
            }

            if (ChunkStageHashStorage.enableBinaryDump) {
                val result = ChunkStageHashStorage.computeBlockHashWithData(chunk.sections.asIterable())
                ChunkStageHashStorage.storeHash(tracked.pos, dimension, ChunkStatus.FEATURES.toString(), result.hash)
                ChunkStageHashStorage.storeBlockData(tracked.pos, dimension, ChunkStatus.FEATURES.toString(), result.sectionData)
            } else {
                val hash = ChunkStageHashStorage.computeBlockHash(chunk.sections.asIterable())
                ChunkStageHashStorage.storeHash(tracked.pos, dimension, ChunkStatus.FEATURES.toString(), hash)
            }
            captured++
        }
        logger.info("Captured final feature hashes for $captured/${trackedChunks.size} chunks in $dimension")
    }

    /**
     * Write per-dimension per-stage gzip-compressed binary files containing raw block state IDs.
     *
     * Format (all integers big-endian):
     *   chunk_count: i32
     *   For each chunk (sorted by x, z):
     *     chunk_x: i32
     *     chunk_z: i32
     *     section_count: i32
     *     For each section (bottom to top):
     *       has_data: u8 (0 = all air, 1 = has block data)
     *       if has_data == 1:
     *         state_ids: [i32; 4096] in YZX order
     */
    fun writeBinaryBlockData(outputDir: Path) {
        val allData = ChunkStageHashStorage.getAllBlockData()
        if (allData.isEmpty()) {
            logger.warn("No block data stored, skipping binary output")
            return
        }

        val dimGroups = allData.entries.groupBy { it.key.first.dimension }

        for ((dimension, dimEntries) in dimGroups) {
            val dimShort = dimension.removePrefix("minecraft:")
            val stageGroups = dimEntries.groupBy { it.key.second }

            for ((stageName, entries) in stageGroups) {
                val shortName = stageName.removePrefix("minecraft:")
                val fileName = "chunk_stage_${dimShort}_${shortName}_blocks.bin.gz"
                val outputPath = outputDir.resolve("steel-core/test_assets/$fileName")
                Files.createDirectories(outputPath.parent)

                val chunksByPos = entries
                    .map { it.key.first.pos to it.value }
                    .sortedWith(compareBy({ it.first.x }, { it.first.z }))

                GZIPOutputStream(Files.newOutputStream(outputPath)).use { gzip ->
                    DataOutputStream(gzip).use { dos ->
                        dos.writeInt(chunksByPos.size)
                        for ((pos, sectionData) in chunksByPos) {
                            dos.writeInt(pos.x)
                            dos.writeInt(pos.z)
                            dos.writeInt(sectionData.size)
                            for (section in sectionData) {
                                if (section == null) {
                                    dos.writeByte(0)
                                } else {
                                    dos.writeByte(1)
                                    for (stateId in section) {
                                        dos.writeInt(stateId)
                                    }
                                }
                            }
                        }
                    }
                }

                logger.info("Wrote binary block data for $dimension stage '$stageName': ${chunksByPos.size} chunks -> $outputPath")
            }
        }
    }
}
