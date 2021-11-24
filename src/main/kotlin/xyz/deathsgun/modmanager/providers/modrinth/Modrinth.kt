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
import net.minecraft.text.TranslatableText
import org.apache.http.client.utils.URIBuilder
import org.apache.logging.log4j.LogManager
import xyz.deathsgun.modmanager.ModManager
import xyz.deathsgun.modmanager.api.http.*
import xyz.deathsgun.modmanager.api.mod.*
import xyz.deathsgun.modmanager.api.provider.IModProvider
import xyz.deathsgun.modmanager.api.provider.IModUpdateProvider
import xyz.deathsgun.modmanager.api.provider.Sorting
import xyz.deathsgun.modmanager.providers.modrinth.models.DetailedMod
import xyz.deathsgun.modmanager.providers.modrinth.models.ModrinthVersion
import xyz.deathsgun.modmanager.providers.modrinth.models.SearchResult
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalSerializationApi::class)
class Modrinth : IModProvider, IModUpdateProvider {

    private val logger = LogManager.getLogger("Modrinth")
    private val categories: ArrayList<Category> = ArrayList()
    private val baseUri = "https://api.modrinth.com"


    override fun getName(): String {
        return "Modrinth"
    }

    override fun getCategories(): CategoriesResult {
        if (this.categories.isNotEmpty()) {
            return CategoriesResult.Success(this.categories)
        }
        return try {
            val response = HttpClient.get("${baseUri}/api/v1/tag/category")
            val categories = json.decodeFromString<List<String>>(response.decodeToString())
            for (category in categories) {
                if (category == "fabric") { // Fabric is not really a category
                    continue
                }
                this.categories.add(
                    Category(
                        category,
                        TranslatableText(String.format("modmanager.category.%s", category))
                    )
                )
            }
            CategoriesResult.Success(this.categories)
        } catch (e: Exception) {
            logger.error("Error while getting categories: {}", e.message)
            CategoriesResult.Error(TranslatableText("modmanager.error.failedToParse", e.message), e)
        }
    }

    override fun getMods(sorting: Sorting, page: Int, limit: Int): ModsResult {
        val builder = URIBuilder("${baseUri}/api/v1/mod")
        builder.addParameter("filters", "categories=\"fabric\" AND NOT client_side=\"unsupported\"")
        return getMods(builder, sorting, page, limit)
    }

    override fun getMods(categories: List<Category>, sorting: Sorting, page: Int, limit: Int): ModsResult {
        val builder = URIBuilder("${baseUri}/api/v1/mod")
        builder.addParameter(
            "filters",
            "categories=\"fabric\" AND NOT client_side=\"unsupported\"${filterFromCategories(categories)}"
        )

        return try {
            getMods(builder, sorting, page, limit)
        } catch (e: Exception) {
            ModsResult.Error(TranslatableText("modmanager.error.unknown", e.message), e)
        }
    }

    override fun search(
        query: String,
        categories: List<Category>,
        sorting: Sorting,
        page: Int,
        limit: Int
    ): ModsResult {
        val builder = URIBuilder("${baseUri}/api/v1/mod")
        builder.addParameter("query", query)
        builder.addParameter(
            "filters",
            "categories=\"fabric\" AND NOT client_side=\"unsupported\"${filterFromCategories(categories)}"
        )
        return try {
            getMods(builder, sorting, page, limit)
        } catch (e: Exception) {
            ModsResult.Error(TranslatableText("modmanager.error.unknown", e.message), e)
        }
    }

    private fun filterFromCategories(categories: List<Category>): String {
        var categoriesFilter = ""
        for (category in categories) {
            categoriesFilter += "AND categories=\"${category.id}\""
        }
        categoriesFilter = categoriesFilter.replaceFirst("AND ", " AND (")
        if (categories.isNotEmpty()) {
            categoriesFilter += ")"
        }
        return categoriesFilter
    }

    private fun getMods(builder: URIBuilder, sorting: Sorting, page: Int, limit: Int): ModsResult {
        builder.addParameter(
            "version",
            String.format("versions=%s", ModManager.getMinecraftVersion())
        )
        builder.addParameter("index", sorting.name.lowercase())
        builder.addParameter("offset", (page * limit).toString())
        builder.addParameter("limit", limit.toString())
        val response = HttpClient.get(builder.build())
        return try {
            val result = json.decodeFromString<SearchResult>(response.decodeToString())
            ModsResult.Success(result.toList())
        } catch (e: Exception) {
            logger.error("Error while requesting mods {}", e.message)
            if (e is HttpClient.InvalidStatusCodeException) {
                ModsResult.Error(TranslatableText("modmanager.error.invalidStatus", e.message))
            }
            ModsResult.Error(TranslatableText("modmanager.error.failedToParse", e.message))
        }
    }

    private val json = Json {
        this.ignoreUnknownKeys = true
    }

    override fun getMod(id: String): ModResult {
        id.replaceFirst("local-", "")
        val response = try {
            HttpClient.get("${baseUri}/api/v1/mod/${id}")
        } catch (e: Exception) {
            logger.error("Error while getting mod {}", e.message)
            if (e is HttpClient.InvalidStatusCodeException) {
                return ModResult.Error(TranslatableText("modmanager.error.invalidStatus", e.statusCode))
            }
            return ModResult.Error(TranslatableText("modmanager.error.network", e.message), e)
        }
        return try {
            val result = json.decodeFromString<DetailedMod>(response.decodeToString())
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
                    id = result.id.replaceFirst("local-", ""),
                    slug = result.slug,
                    author = null,
                    name = result.title,
                    shortDescription = result.description,
                    iconUrl = result.iconUrl,
                    description = result.body,
                    license = result.license.name,
                    categories = categoriesList
                )
            )
        } catch (e: Exception) {
            ModResult.Error(TranslatableText("modmanager.error.failedToParse", e.message), e)
        }
    }

    override fun getVersionsForMod(id: String): VersionResult {
        val response = try {
            HttpClient.get("${baseUri}/api/v1/mod/${id}/version")
        } catch (e: Exception) {
            if (e is HttpClient.InvalidStatusCodeException) {
                return VersionResult.Error(TranslatableText("modmanager.error.invalidStatus", e.statusCode))
            }
            logger.error("Error while getting mod {}", e.message)
            return VersionResult.Error(TranslatableText("modmanager.error.network", e.message), e)
        }
        return try {
            val modrinthVersions = json.decodeFromString<List<ModrinthVersion>>(response.decodeToString())
            val versions = ArrayList<Version>()
            for (modVersion in modrinthVersions) {
                if (!modVersion.loaders.contains("fabric")) {
                    continue
                }
                val assets = ArrayList<Asset>()
                for (file in modVersion.files) {
                    assets.add(Asset(file.url, file.filename, file.hashes, file.primary))
                }
                versions.add(
                    Version(
                        modVersion.version,
                        modVersion.changelog,
                        // 2021-09-03T10:56:59.402790Z
                        Instant.parse(modVersion.releaseDate).atOffset(
                            ZoneOffset.UTC
                        ).toLocalDate(),
                        getVersionType(modVersion.type),
                        modVersion.gameVersions,
                        assets
                    )
                )
            }
            VersionResult.Success(versions)
        } catch (e: Exception) {
            VersionResult.Error(TranslatableText("modmanager.error.failedToParse", e.message), e)
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