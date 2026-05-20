package com.steelextractor

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.steelextractor.extractors.Attributes
import com.steelextractor.extractors.Classes
import com.steelextractor.extractors.BlockEntities
import com.steelextractor.extractors.Blocks
import com.steelextractor.extractors.Entities
import com.steelextractor.extractors.EntityEvents
import com.steelextractor.extractors.Fluids
import com.steelextractor.extractors.GameRulesExtractor
import com.steelextractor.extractors.Items
import com.steelextractor.extractors.MenuTypes
import com.steelextractor.extractors.MobEffects
import com.steelextractor.extractors.Packets
import com.steelextractor.extractors.LevelEvents
import com.steelextractor.extractors.SoundEvents
import com.steelextractor.extractors.SoundTypes
import com.steelextractor.extractors.MultiNoiseBiomeParameters
import com.steelextractor.extractors.BiomeHashes
import com.steelextractor.extractors.CandleCakes
import com.steelextractor.extractors.ChunkStageHashes
import com.steelextractor.extractors.Weathering
import com.steelextractor.extractors.Strippables
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import com.steelextractor.extractors.PoiTypesExtractor
import com.steelextractor.extractors.Potions
import com.steelextractor.extractors.StructureStarts
import com.steelextractor.extractors.Tags
import com.steelextractor.extractors.Waxables
import kotlinx.io.IOException
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.system.measureTimeMillis

object SteelExtractor : ModInitializer {
    private val logger = LoggerFactory.getLogger("steel-extractor")

    /** Set to false to skip chunk generation and chunk stage hash extraction. */
    private const val ENABLE_CHUNK_EXTRACTION = true

    /** Set to false to skip storing per-chunk block data in memory and writing binary dump files. */
    private const val ENABLE_BINARY_DUMP = true

    /** Sampling parameters: place random CLUSTER_SIZE x CLUSTER_SIZE clusters within a SAMPLE_HALF_RANGE*2 x SAMPLE_HALF_RANGE*2 area. */
    const val CHUNK_SAMPLE_SEED: Long = 123456
    private const val CLUSTER_SIZE: Int = 10 // 10x10 chunks per cluster
    private const val CHUNKS_PER_CLUSTER: Int = CLUSTER_SIZE * CLUSTER_SIZE
    private const val NUM_CLUSTERS: Int = 25 // 25 clusters * 100 = 2,500 chunks
    const val NUM_SAMPLE_CHUNKS: Int = NUM_CLUSTERS * CHUNKS_PER_CLUSTER
    private const val SAMPLE_HALF_RANGE: Int = 500_000 // 1000,000x1000,000 chunk area
    private const val CARVER_CHUNKS_PER_TICK = CHUNKS_PER_CLUSTER
    private const val FEATURE_CHUNKS_PER_TICK = CHUNKS_PER_CLUSTER
    private val CHUNK_POSITION_ORDER: Comparator<ChunkPos> = compareBy({ it.x }, { it.z })
    private const val DEBUG_CLUSTER_ENV = "STEEL_EXTRACTOR_DEBUG_CLUSTER"
    private const val DEBUG_DIMENSION_ENV = "STEEL_EXTRACTOR_DEBUG_DIMENSION"
    private const val DEBUG_SKIP_IMMEDIATE_ENV = "STEEL_EXTRACTOR_SKIP_IMMEDIATE"

    /** Generate the same sampled chunk clusters used by chunk stage hash extraction. */
    fun sampledChunkClusters(): List<List<ChunkPos>> {
        val rng = Random(CHUNK_SAMPLE_SEED)
        val clusters = mutableListOf<List<ChunkPos>>()
        for (i in 0 until NUM_CLUSTERS) {
            val originX = rng.nextInt(-SAMPLE_HALF_RANGE, SAMPLE_HALF_RANGE)
            val originZ = rng.nextInt(-SAMPLE_HALF_RANGE, SAMPLE_HALF_RANGE)
            val positions = mutableListOf<ChunkPos>()
            for (dx in 0 until CLUSTER_SIZE) {
                for (dz in 0 until CLUSTER_SIZE) {
                    positions.add(ChunkPos(originX + dx, originZ + dz))
                }
            }
            clusters.add(positions)
        }
        return clusters
    }

    /** Generate the same sampled chunk positions used by chunk stage hash extraction. */
    fun sampledChunkPositions(): List<ChunkPos> {
        return sampledChunkClusters().flatten()
    }

    private fun focusedChunkCluster(origin: ChunkPos): List<ChunkPos> {
        val positions = mutableListOf<ChunkPos>()
        for (dx in 0 until CLUSTER_SIZE) {
            for (dz in 0 until CLUSTER_SIZE) {
                positions.add(ChunkPos(origin.x + dx, origin.z + dz))
            }
        }
        return positions
    }

    private fun debugClusterOrigin(): ChunkPos? {
        val value = System.getenv(DEBUG_CLUSTER_ENV)?.takeIf { it.isNotBlank() } ?: return null
        val parts = value.split(",", limit = 2)
        require(parts.size == 2) { "$DEBUG_CLUSTER_ENV must be formatted as '<chunk_x>,<chunk_z>'" }
        val x = parts[0].toIntOrNull()
            ?: error("$DEBUG_CLUSTER_ENV chunk_x is not an integer: ${parts[0]}")
        val z = parts[1].toIntOrNull()
            ?: error("$DEBUG_CLUSTER_ENV chunk_z is not an integer: ${parts[1]}")
        return ChunkPos(x, z)
    }

    private fun envFlag(name: String): Boolean {
        val value = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return false
        return value == "1" || value.equals("true", ignoreCase = true) || value.equals("yes", ignoreCase = true)
    }

    override fun onInitialize() {
        logger.info("Hello Fabric world!")

        val test = BuiltInRegistries.BLOCK.byId(5);
        logger.info(test.toString())

        val test2 = BuiltInRegistries.FLUID.byId(2)
        logger.info(test2.toString())

        val immediateExtractors = arrayOf(
            Blocks(),
            BlockEntities(),
            Items(),
            Packets(),
            MenuTypes(),
            Entities(),
            EntityEvents(),
            Fluids(),
            GameRulesExtractor(),
            Classes(),
            Attributes(),
            MobEffects(),
            Potions(),
            SoundTypes(),
            SoundEvents(),
            MultiNoiseBiomeParameters(),
            BiomeHashes(),
            LevelEvents(),
            Tags(),
            StructureStarts(),
            Strippables(),
            Weathering(),
            CandleCakes(),
            Waxables(),
            PoiTypesExtractor()
        )


        val chunkStageExtractor = ChunkStageHashes()

        val allDimensions = listOf(
            "minecraft:overworld" to Level.OVERWORLD,
            "minecraft:the_nether" to Level.NETHER,
            "minecraft:the_end" to Level.END
        )

        val debugDimension = System.getenv(DEBUG_DIMENSION_ENV)?.takeIf { it.isNotBlank() }
        val dimensions = if (debugDimension == null) {
            allDimensions
        } else {
            val selected = allDimensions.filter { (dimId, _) -> dimId == debugDimension }
            require(selected.isNotEmpty()) {
                "$DEBUG_DIMENSION_ENV must be one of ${allDimensions.joinToString { it.first }}"
            }
            logger.warn("Focused chunk extraction enabled for dimension $debugDimension")
            selected
        }

        val debugClusterOrigin = debugClusterOrigin()
        val sampledClusters = if (debugClusterOrigin == null) {
            sampledChunkClusters()
        } else {
            logger.warn("Focused chunk extraction enabled for cluster origin (${debugClusterOrigin.x}, ${debugClusterOrigin.z})")
            listOf(focusedChunkCluster(debugClusterOrigin))
        }
        val sampledPositions = sampledClusters.flatten()
        val generationClusters = sampledClusters
            .map { cluster -> cluster.sortedWith(CHUNK_POSITION_ORDER) }
            .sortedWith(compareBy({ it.first().x }, { it.first().z }))
        val clusterCount = generationClusters.size

        if (ENABLE_CHUNK_EXTRACTION) {
            ServerLifecycleEvents.SERVER_STARTING.register { _ ->
                logger.info("Setting up chunk stage hash tracking (${sampledPositions.size} sampled chunks from ${SAMPLE_HALF_RANGE * 2}x${SAMPLE_HALF_RANGE * 2} area, ${dimensions.size} dimensions)")
                val chunksToTrack = mutableSetOf<DimChunkPos>()
                for ((dimId, _) in dimensions) {
                    for (pos in sampledPositions) {
                        chunksToTrack.add(DimChunkPos(pos, dimId))
                    }
                }
                ChunkStageHashStorage.enableBinaryDump = ENABLE_BINARY_DUMP
                ChunkStageHashStorage.startTracking(chunksToTrack)
            }
        } else {
            logger.info("Chunk extraction DISABLED")
        }

        val outputDirectory: Path
        try {
            outputDirectory = Files.createDirectories(Paths.get("steel_extractor_output"))
        } catch (e: IOException) {
            logger.info("Failed to create output directory.", e)
            return
        }

        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleEvents.ServerStarted { server: MinecraftServer ->
            if (envFlag(DEBUG_SKIP_IMMEDIATE_ENV)) {
                logger.warn("Skipping immediate extractors because $DEBUG_SKIP_IMMEDIATE_ENV is enabled")
            } else {
                val timeInMillis = measureTimeMillis {
                    for (ext in immediateExtractors) {
                        runExtractor(ext, outputDirectory, gson, server)
                    }
                }
                logger.info("Immediate extractors done, took ${timeInMillis}ms")
            }


            if (!ENABLE_CHUNK_EXTRACTION) {
                logger.info("All extractors complete! (chunk extraction skipped)")
            }
        })

        if (!ENABLE_CHUNK_EXTRACTION) return

        // Build per-dimension chunk queues. Features are order-dependent because
        // they can write into neighboring chunks, so keep the vanilla fixture order
        // aligned with the serialized JSON/test order.
        data class ClusterWork(
            val positions: List<ChunkPos>,
            val carverQueue: ArrayDeque<ChunkPos>,
            val featureQueue: ArrayDeque<ChunkPos>,
            val featureChunks: MutableMap<ChunkPos, ChunkAccess> = mutableMapOf()
        )

        data class DimensionWork(
            val dimensionKey: ResourceKey<Level>,
            val dimId: String,
            val clusters: ArrayDeque<ClusterWork>,
            var carverProgress: Int = 0,
            var featureProgress: Int = 0
        )

        val dimWork = dimensions.map { (dimId, key) ->
            val clusters = ArrayDeque<ClusterWork>()
            for (positions in generationClusters) {
                val carverQueue = ArrayDeque<ChunkPos>()
                val featureQueue = ArrayDeque<ChunkPos>()
                carverQueue.addAll(positions)
                featureQueue.addAll(positions)
                clusters.add(ClusterWork(positions, carverQueue, featureQueue))
            }
            DimensionWork(key, dimId, clusters)
        }
        val chunksPerDim = sampledPositions.size
        val totalChunks = chunksPerDim * dimWork.size
        val totalChunkSteps = totalChunks * 2

        var generationStarted = false
        var currentDimIdx = 0
        var allGenerationDone = false
        var chunkExtractorDone = false
        var manuallyMarked = 0

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (chunkExtractorDone) return@register

            // Start generation on first tick after server is ready
            if (!generationStarted) {
                generationStarted = true
                logger.info("Forcing deterministic generation of $totalChunks chunks across ${dimWork.size} dimensions (carvers $CARVER_CHUNKS_PER_TICK/tick, features $FEATURE_CHUNKS_PER_TICK/tick, order x/z ascending)...")
            }

            // Generate a batch of chunks per tick, one dimension at a time
            if (!allGenerationDone) {
                val dim = dimWork[currentDimIdx]
                ChunkStageHashStorage.currentDimension = dim.dimId
                val level: ServerLevel = server.getLevel(dim.dimensionKey) ?: run {
                    logger.warn("Could not get level for ${dim.dimId}, skipping")
                    currentDimIdx++
                    if (currentDimIdx >= dimWork.size) allGenerationDone = true
                    return@register
                }

                val cluster = dim.clusters.firstOrNull() ?: run {
                    logger.info("Finished generating chunks for ${dim.dimId}")
                    currentDimIdx++
                    if (currentDimIdx >= dimWork.size) {
                        if (manuallyMarked > 0) {
                            logger.warn("$manuallyMarked chunks were loaded from disk (no intermediate stage hashes). Delete the world folder for full tracking.")
                        }
                        allGenerationDone = true
                        logger.info("All chunk generation complete, waiting for all stages...")
                    }
                    return@register
                }

                val (queue, status, batchSize) = if (cluster.carverQueue.isNotEmpty()) {
                    Triple(cluster.carverQueue, ChunkStatus.CARVERS, CARVER_CHUNKS_PER_TICK)
                } else {
                    Triple(cluster.featureQueue, ChunkStatus.FEATURES, FEATURE_CHUNKS_PER_TICK)
                }

                var generatedThisTick = 0
                while (queue.isNotEmpty() && generatedThisTick < batchSize) {
                    val pos = queue.removeFirst()
                    val chunk = level.getChunk(pos.x, pos.z, status, true)
                    if (status == ChunkStatus.FEATURES) {
                        if (chunk != null) {
                            cluster.featureChunks[pos] = chunk
                        }
                        dim.featureProgress++
                    } else {
                        dim.carverProgress++
                    }
                    generatedThisTick++
                }

                val dimProgress = dim.carverProgress + dim.featureProgress
                val overallProgress = currentDimIdx * chunksPerDim * 2 + dimProgress
                val clusterNumber = clusterCount - dim.clusters.size + 1
                logger.info("Chunk generation progress: $overallProgress/$totalChunkSteps (${dim.dimId}: cluster $clusterNumber/$clusterCount, carvers ${dim.carverProgress}/$chunksPerDim, features ${dim.featureProgress}/$chunksPerDim)")

                if (cluster.carverQueue.isEmpty() && cluster.featureQueue.isEmpty()) {
                    // Mark any chunks loaded from disk as ready.
                    for (pos in cluster.positions) {
                        if (ChunkStageHashStorage.markReady(pos, dim.dimId)) {
                            manuallyMarked++
                        }
                    }
                    chunkStageExtractor.captureFinalFeatureHashes(
                        server,
                        dim.dimId,
                        cluster.positions,
                        cluster.featureChunks
                    )
                    dim.clusters.removeFirst()
                }

                return@register
            }

            // Wait for all chunks to finish all stages
            if (ChunkStageHashStorage.getReadyCount() >= ChunkStageHashStorage.getTrackedCount()) {
                chunkExtractorDone = true
                try {
                    val out = outputDirectory.resolve(chunkStageExtractor.fileName())
                    Files.createDirectories(out.parent)
                    val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
                    gson.toJson(chunkStageExtractor.extract(server), fileWriter)
                    fileWriter.close()
                    logger.info("Wrote " + out.toAbsolutePath())
                } catch (e: java.lang.Exception) {
                    logger.error("Extractor for \"${chunkStageExtractor.fileName()}\" failed.", e)
                }
                if (ENABLE_BINARY_DUMP) {
                    try {
                        chunkStageExtractor.writeBinaryBlockData(outputDirectory)
                    } catch (e: java.lang.Exception) {
                        logger.error("Binary block data extraction failed.", e)
                    }
                }
                logger.info("All extractors complete!")
            }
        }
    }

    private fun runExtractor(
        ext: Extractor,
        outputDirectory: Path,
        gson: com.google.gson.Gson,
        server: MinecraftServer
    ) {
        try {
            val out = outputDirectory.resolve(ext.fileName())
            Files.createDirectories(out.parent)
            val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
            gson.toJson(ext.extract(server), fileWriter)
            fileWriter.close()
            logger.info("Wrote " + out.toAbsolutePath())
        } catch (e: java.lang.Exception) {
            logger.error("Extractor for \"${ext.fileName()}\" failed.", e)
        }
    }

    interface Extractor {
        fun fileName(): String

        @Throws(Exception::class)
        fun extract(server: MinecraftServer): JsonElement
    }
}
