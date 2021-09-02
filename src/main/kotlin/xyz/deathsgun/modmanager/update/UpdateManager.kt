/*
 * Copyright 2021 DeathsGun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.deathsgun.modmanager.update

import com.terraformersmc.modmenu.util.mod.fabric.CustomValueUtil
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.SemanticVersion
import net.fabricmc.loader.api.metadata.ModMetadata
import net.fabricmc.loader.util.version.VersionDeserializer
import org.apache.logging.log4j.LogManager
import xyz.deathsgun.modmanager.ModManager
import xyz.deathsgun.modmanager.api.http.ModsResult
import xyz.deathsgun.modmanager.api.http.VersionResult
import xyz.deathsgun.modmanager.api.mod.Mod
import xyz.deathsgun.modmanager.api.mod.Version
import xyz.deathsgun.modmanager.api.provider.IModUpdateProvider
import xyz.deathsgun.modmanager.models.FabricMetadata
import xyz.deathsgun.modmanager.state.ModState
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.ZipFile
import kotlin.io.path.name


class UpdateManager {

    private val logger = LogManager.getLogger("UpdateCheck")
    private val blockedIds = arrayOf("java", "minecraft")
    val updates = ArrayList<Update>()

    suspend fun checkUpdates() = coroutineScope {
        val mods = getCheckableMods()
        mods.forEach { metadata ->
            launch {
                if (findJarByModContainer(metadata) == null) {
                    logger.info("Skipping update for {} because it has no jar in mods", metadata.id)
                    return@launch
                }
                val configIds = getIdBy(metadata)
                if (configIds == null) {
                    logger.info("Searching for updates for {} using fallback method", metadata.id)
                    checkForUpdatesManually(metadata)
                    return@launch
                }
                logger.info("Searching for updates for {} using defined mod id", metadata.id)
                checkForUpdates(metadata, configIds)
            }
        }
    }

    private fun checkForUpdatesManually(metadata: ModMetadata) {
        val defaultProvider = ModManager.modManager.config.defaultProvider
        val provider = ModManager.modManager.provider[defaultProvider]
        if (provider == null) {
            logger.warn("Default provider {} not found", defaultProvider)
            return
        }
        val updateProvider = ModManager.modManager.updateProvider[defaultProvider]
        if (updateProvider == null) {
            logger.warn("Default update provider {} not found", defaultProvider)
            return
        }
        var result = updateProvider.getVersionsForMod(metadata.id)
        if (result is VersionResult.Success) {
            val version = findLatestCompatible(metadata.version.friendlyString, result.versions)
            if (version == null) {
                logger.info("No update for {} found!", metadata.id)
                ModManager.modManager.setModState(metadata.id, metadata.id, ModState.INSTALLED)
                return
            }
            logger.info("Update for {} found [{} -> {}]", metadata.id, metadata.version.friendlyString, version.version)
            ModManager.modManager.setModState(metadata.id, metadata.id, ModState.OUTDATED)
            this.updates.add(Update(metadata.id, metadata.id, version))
            return
        }

        val queryResult = provider.search(metadata.name, 0, 10)
        if (queryResult is ModsResult.Error) {
            logger.warn("Error while searching for fallback id for mod {}: ", metadata.id, queryResult.cause)
            ModManager.modManager.setModState(metadata.id, metadata.id, ModState.INSTALLED)
            return
        }
        val mod =
            (queryResult as ModsResult.Success).mods.find { mod -> mod.slug == metadata.id || mod.name == metadata.name }
        if (mod == null) {
            logger.warn("Error while searching for fallback id for mod {}: No possible match found", metadata.id)
            ModManager.modManager.setModState(metadata.id, metadata.id, ModState.INSTALLED)
            return
        }
        result = updateProvider.getVersionsForMod(mod.id)
        val versions = when (result) {
            is VersionResult.Error -> {
                logger.error("Error while getting versions for mod {}", metadata.id)
                ModManager.modManager.setModState(metadata.id, mod.id, ModState.INSTALLED)
                return
            }
            is VersionResult.Success -> result.versions
        }
        val version = findLatestCompatible(metadata.version.friendlyString, versions)
        if (version == null) {
            logger.info("No update for {} found!", metadata.id)
            ModManager.modManager.setModState(metadata.id, mod.id, ModState.INSTALLED)
            return
        }
        logger.info("Update for {} found [{} -> {}]", metadata.id, metadata.version.friendlyString, version.version)
        ModManager.modManager.setModState(metadata.id, mod.id, ModState.OUTDATED)
        this.updates.add(Update(mod.id, metadata.id, version))
    }

    private fun checkForUpdates(metadata: ModMetadata, ids: Map<String, String>) {
        var provider: IModUpdateProvider? = null
        var id: String? = null
        for ((provId, modId) in ids) {
            val providerId = provId.lowercase()
            if (!ModManager.modManager.updateProvider.containsKey(providerId)) {
                logger.warn("Update provider {} for {} not found!", providerId, metadata.id)
                continue
            }
            provider = ModManager.modManager.updateProvider[providerId]!!
            id = modId
        }
        if (provider == null || id == null) {
            logger.warn("No valid provider for {} found! Skipping", metadata.id)
            ModManager.modManager.setModState(metadata.id, id ?: metadata.id, ModState.INSTALLED)
            return
        }
        val versions = when (val result = provider.getVersionsForMod(id)) {
            is VersionResult.Error -> {
                logger.error("Error while getting versions for mod {}", metadata.id)
                ModManager.modManager.setModState(metadata.id, id, ModState.INSTALLED)
                return
            }
            is VersionResult.Success -> result.versions
        }
        val version = findLatestCompatible(metadata.version.friendlyString, versions)
        if (version == null) {
            logger.info("No update for {} found!", metadata.id)
            ModManager.modManager.setModState(metadata.id, id, ModState.INSTALLED)
            return
        }
        logger.info("Update for {} found [{} -> {}]", metadata.id, metadata.version.friendlyString, version.version)
        ModManager.modManager.setModState(metadata.id, id, ModState.OUTDATED)
        this.updates.add(Update(id, metadata.id, version))
    }

    fun getUpdateForMod(mod: Mod): Update? {
        return this.updates.find { it.modId == mod.id || it.fabricId == mod.slug }
    }

    private fun findLatestCompatible(installedVersion: String, versions: List<Version>): Version? {
        var latest: Version? = null
        var latestVersion: SemanticVersion? = null
        val installVersion =
            VersionDeserializer.deserializeSemantic(installedVersion.split("+")[0]) // Remove additional info from version
        for (version in versions) {
            if (!version.gameVersions.contains(ModManager.getMinecraftVersion()) ||
                !ModManager.modManager.config.isReleaseAllowed(version.type)
            ) {
                continue
            }
            val ver =
                VersionDeserializer.deserializeSemantic(version.version.split("+")[0]) // Remove additional info from version
            if (latestVersion == null || ver > latestVersion) {
                latest = version
                latestVersion = ver
            }
        }
        if (latestVersion?.compareTo(installVersion) == 0) {
            return null
        }
        return latest
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun findJarByModContainer(container: ModMetadata): Path? {
        val jars = Files.list(FabricLoader.getInstance().gameDir.resolve("mods"))
            .filter { it.name.endsWith(".jar") }.collect(Collectors.toList())
        return try {
            for (jar in jars) {
                val jarFile = ZipFile(jar.toFile())
                val fabricEntry = jarFile.getEntry("fabric.mod.json")
                val data = jarFile.getInputStream(fabricEntry).bufferedReader().use { it.readText() }
                val meta = json.decodeFromString<FabricMetadata>(data)
                jarFile.close()
                if (meta.id == container.id) {
                    return jar
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getIdBy(metadata: ModMetadata): Map<String, String>? {
        if (!metadata.containsCustomValue("modmanager")) {
            return null
        }
        val ids = HashMap<String, String>()
        val map = metadata.getCustomValue("modmanager").asObject
        map.forEach {
            ids[it.key] = it.value.asString
        }
        return ids
    }

    private fun getCheckableMods(): List<ModMetadata> {
        return FabricLoader.getInstance().allMods.map { it.metadata }.filter {
            !it.id.startsWith("fabric") &&
                    !CustomValueUtil.getBoolean("fabric-loom:generated", it).orElse(false) &&
                    !blockedIds.contains(it.id)
        }
    }

}