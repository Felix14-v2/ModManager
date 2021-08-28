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

package xyz.deathsgun.modmanager.providers.modrinth

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.minecraft.client.MinecraftClient
import net.minecraft.text.TranslatableText
import org.apache.http.client.utils.URIBuilder
import org.apache.logging.log4j.LogManager
import xyz.deathsgun.modmanager.ModManager
import xyz.deathsgun.modmanager.api.http.CategoriesResult
import xyz.deathsgun.modmanager.api.http.ModResult
import xyz.deathsgun.modmanager.api.http.ModsResult
import xyz.deathsgun.modmanager.api.http.VersionResult
import xyz.deathsgun.modmanager.api.mod.*
import xyz.deathsgun.modmanager.api.provider.IModProvider
import xyz.deathsgun.modmanager.api.provider.IModUpdateProvider
import xyz.deathsgun.modmanager.api.provider.Sorting
import xyz.deathsgun.modmanager.providers.modrinth.models.DetailedMod
import xyz.deathsgun.modmanager.providers.modrinth.models.ModrinthVersion
import xyz.deathsgun.modmanager.providers.modrinth.models.SearchResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@OptIn(ExperimentalSerializationApi::class)
class Modrinth : IModProvider, IModUpdateProvider {

    private val logger = LogManager.getLogger("Modrinth")
    private val categories: ArrayList<Category> = ArrayList()
    private val cache: HashMap<String, ModsResult> = HashMap()
    private val baseUri = "https://api.modrinth.com"
    private val http = HttpClient.newHttpClient()


    override fun getName(): String {
        return "Modrinth"
    }

    override fun getCategories(): CategoriesResult {
        if (this.categories.isNotEmpty()) {
            return CategoriesResult.Success(this.categories)
        }
        logger.info("Getting categories")
        val request = HttpRequest.newBuilder().GET().setHeader("User-Agent", "ModManager " + ModManager.getVersion())
            .uri(URI.create("${baseUri}/api/v1/tag/category")).build()
        val response = this.http.send(request, HttpResponse.BodyHandlers.ofString())
        return try {
            val categories = json.decodeFromString<List<String>>(response.body())
            for (category in categories) {
                this.categories.add(
                    Category(
                        category,
                        TranslatableText(String.format("modmanager.category.%s", category))
                    )
                )
            }
            CategoriesResult.Success(this.categories)
        } catch (e: Exception) {
            logger.error("Error while getting categories: ", e)
            CategoriesResult.Error(TranslatableText("modmanager.response.failedToParse"), e)
        }
    }

    override fun getMods(sorting: Sorting, page: Int, limit: Int): ModsResult {
        logger.info("Getting a general list of mods")
        val builder = URIBuilder("${baseUri}/api/v1/mod")
        builder.addParameter("index", sorting.name.lowercase())
        builder.addParameter("filters", "categories=\"fabric\"")
        return getMods(builder, page, limit)
    }

    override fun getMods(category: Category, page: Int, limit: Int): ModsResult {
        logger.info("Getting category '{}' from Modrinth", category.id)
        val key = java.lang.String.format("%s|%d|%d", category.id, page, limit)
        if (this.cache.containsKey(key)) {
            val cachedResult = this.cache[key]!!
            if (cachedResult is ModsResult.Success) {
                return cachedResult
            }
        }
        val builder = URIBuilder("${baseUri}/api/v1/mod")
        builder.addParameter(
            "filters",
            String.format(
                "categories=\"fabric\" AND NOT client_side=\"unsupported\" AND categories=\"%s\"",
                category.id
            )
        )
        return try {
            val result = getMods(builder, page, limit)
            if (result is ModsResult.Success) {
                this.cache[key] = result
            }
            result
        } catch (e: Exception) {
            ModsResult.Error(TranslatableText("modmanager.error.unknown"), e)
        }
    }

    override fun getMods(query: String, page: Int, limit: Int): ModsResult {
        logger.info("Searching for '{}' in Modrinth", query)
        val builder = URIBuilder("${baseUri}/api/v1/mod")
        builder.addParameter("query", query)
        builder.addParameter("filters", "categories=\"fabric\" AND NOT client_side=\"unsupported\"")
        return try {
            getMods(builder, page, limit)
        } catch (e: Exception) {
            ModsResult.Error(TranslatableText("modmanager.error.unknown"), e)
        }
    }

    private fun getMods(builder: URIBuilder, page: Int, limit: Int): ModsResult {
        builder.addParameter(
            "version",
            String.format("versions=%s", MinecraftClient.getInstance()?.game?.version?.releaseTarget ?: "1.17.1")
        )
        builder.addParameter("offset", (page * limit).toString())
        builder.addParameter("limit", limit.toString())
        val request = HttpRequest.newBuilder().GET().setHeader("User-Agent", "ModManager " + ModManager.getVersion())
            .uri(builder.build()).build()
        val response = this.http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            return ModsResult.Error(TranslatableText("modmanager.error.invalidStatus"))
        }
        return try {
            val result = json.decodeFromString<SearchResult>(response.body())
            ModsResult.Success(result.toList())
        } catch (e: Exception) {
            logger.error("Error while requesting mods", e)
            ModsResult.Error(TranslatableText("modmanager.error.failedToParse"))
        }
    }

    private val json = Json {
        this.ignoreUnknownKeys = true
    }

    override fun getMod(mod: Mod): ModResult {
        val id = mod.id.replaceFirst("local-", "")
        val request = HttpRequest.newBuilder().GET()
            .setHeader("User-Agent", "ModManager " + ModManager.getVersion())
            .uri(URI.create("${baseUri}/api/v1/mod/${id}")).build()
        val response = try {
            this.http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            logger.error("Error while getting mod", e)
            return ModResult.Error(TranslatableText("modmanager.error.network"), e)
        }
        if (response.statusCode() != 200) {
            return ModResult.Error(TranslatableText("modmanager.error.invalidStatus"))
        }
        return try {
            val result = json.decodeFromString<DetailedMod>(response.body())
            val categoriesList = ArrayList<Category>()
            result.categories.forEach { categoryId ->
                categoriesList.add(
                    Category(
                        categoryId,
                        TranslatableText("modmanager.category.${categoryId}")
                    )
                )
            }
            ModResult.Success(
                Mod(
                    id = result.id,
                    slug = result.slug,
                    author = mod.author,
                    name = result.title,
                    shortDescription = mod.shortDescription,
                    iconUrl = mod.iconUrl,
                    description = result.body,
                    license = result.license.name,
                    categories = categoriesList
                )
            )
        } catch (e: Exception) {
            ModResult.Error(TranslatableText("modmanager.error.failedToParse"), e)
        }
    }

    override fun getVersionsForMod(id: String): VersionResult {
        val request = HttpRequest.newBuilder().GET()
            .setHeader("User-Agent", "ModManager " + ModManager.getVersion())
            .uri(URI.create("${baseUri}/api/v1/mod/${id}/version")).build()
        val response = try {
            this.http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            logger.error("Error while getting mod", e)
            return VersionResult.Error(TranslatableText("modmanager.error.network"), e)
        }
        if (response.statusCode() != 200) {
            return VersionResult.Error(TranslatableText("modmanager.error.invalidStatus"))
        }
        return try {
            val modrinthVersions = json.decodeFromString<List<ModrinthVersion>>(response.body())
            val versions = ArrayList<Version>()
            for (modVersion in modrinthVersions) {
                val assets = ArrayList<Asset>()
                for (file in modVersion.files) {
                    assets.add(Asset(file.url, file.filename, file.hashes))
                }
                versions.add(
                    Version(
                        modVersion.version,
                        modVersion.changelog,
                        getVersionType(modVersion.type),
                        modVersion.gameVersions,
                        assets
                    )
                )
            }
            VersionResult.Success(versions)
        } catch (e: Exception) {
            VersionResult.Error(TranslatableText("modmanager.error.failedToParse"), e)
        }
    }

    private fun getVersionType(id: String): VersionType {
        return when (id) {
            "release" -> VersionType.RELEASE
            "alpha" -> VersionType.ALPHA
            "beta" -> VersionType.BETA
            else -> VersionType.UNKNOWN
        }
    }

}