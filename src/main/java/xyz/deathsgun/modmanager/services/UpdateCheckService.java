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

package xyz.deathsgun.modmanager.services;

import com.terraformersmc.modmenu.util.mod.fabric.CustomValueUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.util.version.VersionDeserializer;
import net.minecraft.MinecraftVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import xyz.deathsgun.modmanager.ModManager;
import xyz.deathsgun.modmanager.api.mod.DetailedMod;
import xyz.deathsgun.modmanager.api.mod.ModVersion;
import xyz.deathsgun.modmanager.api.mod.SummarizedMod;
import xyz.deathsgun.modmanager.api.mod.VersionType;
import xyz.deathsgun.modmanager.api.provider.IModProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UpdateCheckService extends Thread {

    private final Logger logger = LogManager.getLogger("Update Checker");
    private final ArrayList<AvailableUpdates> updates = new ArrayList<>();
    private final List<String> blockedIds = Arrays.asList("java", "minecraft");

    public UpdateCheckService() {
        super();
        setName("ModManager-U");
    }

    @Override
    public void run() {
        FabricLoader.getInstance().getAllMods().stream().map(ModContainer::getMetadata)
                .filter(metadata -> !metadata.getId().startsWith("fabric") && !metadata.containsCustomValue("fabric-api:module-lifecycle"))
                .filter(metadata -> !CustomValueUtil.getBoolean("fabric-loom:generated", metadata).orElse(false))
                .filter(metadata -> !blockedIds.contains(metadata.getId()))
                .forEach(this::checkForUpdate);
    }

    private void checkForUpdate(ModMetadata metadata) {
        if (!(metadata.getVersion() instanceof SemanticVersion)) {
            LogManager.getLogger().warn("Update checking for mod {} not supported because it has no semantic version scheme", metadata.getId());
            return;
        }
        try {
            String modId = findModId(metadata);
            ModVersion version = getUpdateVersion(modId, metadata);
            if (version == null) {
                return;
            }
            updates.add(new AvailableUpdates(modId, metadata.getId(), version));
        } catch (Exception e) {
            logger.error("Failed to check for updates for {}", metadata.getId());
        }
    }

    private String findModId(ModMetadata container) throws Exception {
        IModProvider provider = ModManager.getModProvider();
        List<SummarizedMod> hits = provider.getMods(container.getName(), 0, 1);
        if (hits.isEmpty()) {
            throw new Exception(String.format("Mod %s not found on %s", container.getId(), provider.getName()));
        }
        return hits.get(0).id();
    }

    @Nullable
    private ModVersion getUpdateVersion(String modId, ModMetadata container) throws Exception {
        IModProvider provider = ModManager.getModProvider();
        SemanticVersion installedVersion = (SemanticVersion) container.getVersion();
        List<ModVersion> versions = provider.getVersionsForMod(modId);
        ModVersion latest = null;
        SemanticVersion latestVersion = null;
        for (ModVersion modVersion : versions) {
            if (!modVersion.gameVersions().contains(MinecraftVersion.GAME_VERSION.getReleaseTarget()) || modVersion.type() != VersionType.RELEASE) {
                continue;
            }
            SemanticVersion version = VersionDeserializer.deserializeSemantic(modVersion.version());
            if (latestVersion == null || (version.compareTo(installedVersion) > 0 && version.compareTo(latestVersion) > 0)) {
                latest = modVersion;
                latestVersion = version;
            }
        }
        if (latest == null) {
            logger.info("No update for {} found!", container.getId());
        }
        return latest;
    }

    public boolean isUpdateAvailable(SummarizedMod mod, ModMetadata modContainer) {
        return this.updates.stream().anyMatch(update -> update.modId.equalsIgnoreCase(mod.id()) || update.fabricModId.equalsIgnoreCase(modContainer.getId()));
    }

    public boolean updatesAvailable() {
        return !this.updates.isEmpty();
    }

    @Nullable
    public ModVersion getUpdate(SummarizedMod mod) {
        for (AvailableUpdates update : this.updates) {
            if (update.modId.equalsIgnoreCase(mod.id())) {
                return update.update;
            }
        }
        return null;
    }

    public int updatesAvailableCount() {
        return this.updates.size();
    }

    public List<DetailedMod> getUpdatableMods() {
        ArrayList<DetailedMod> mods = new ArrayList<>();
        for (AvailableUpdates update : this.updates) {
            try {
                mods.add(ModManager.getModProvider().getMod(update.modId()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return mods;
    }

    private record AvailableUpdates(String modId, String fabricModId,
                                    ModVersion update) {
    }

}
