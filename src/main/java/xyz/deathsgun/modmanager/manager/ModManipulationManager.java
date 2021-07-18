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

package xyz.deathsgun.modmanager.manager;

import xyz.deathsgun.modmanager.ModManager;
import xyz.deathsgun.modmanager.api.manipulation.ErrorHandler;
import xyz.deathsgun.modmanager.api.mod.SummarizedMod;
import xyz.deathsgun.modmanager.tasks.ModDownloadTask;
import xyz.deathsgun.modmanager.tasks.ModRemovalTask;
import xyz.deathsgun.modmanager.util.FabricMods;

import java.util.ArrayList;

public class ModManipulationManager {

    private final ArrayList<String> manuallyInstalled = new ArrayList<>();

    public void installMod(SummarizedMod mod, ErrorHandler errorHandler) {
        ModManager.getManipulationService().add(new ModDownloadTask(mod.id() + "_mod_download", mod, errorHandler));
    }

    public void markManuallyInstalled(SummarizedMod mod) {
        this.manuallyInstalled.add(mod.id());
    }

    public void removeManuallyInstalled(SummarizedMod mod) {
        this.manuallyInstalled.removeIf(s -> mod.id().equals(s));
    }

    public void removeMod(SummarizedMod mod, ErrorHandler errorHandler) {
        ModManager.getManipulationService().add(new ModRemovalTask(mod.id() + "_mod_removal", mod, errorHandler));
    }

    public boolean isInstalled(SummarizedMod mod) {
        return this.manuallyInstalled.contains(mod.id()) || FabricMods.getModContainerByMod(mod).isPresent();
    }
}
