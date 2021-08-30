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

package xyz.deathsgun.modmanager.gui

import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.TranslatableText
import org.lwjgl.glfw.GLFW
import xyz.deathsgun.modmanager.ModManager
import xyz.deathsgun.modmanager.api.http.ModsResult
import xyz.deathsgun.modmanager.gui.widget.ErrorWidget
import xyz.deathsgun.modmanager.gui.widget.ModListWidget

class ModsOverviewScreen(private val previousScreen: Screen) : Screen(TranslatableText("modmanager.title.overview")) {

    private var query: String = ""
    private lateinit var errorWidget: ErrorWidget
    private lateinit var searchField: TextFieldWidget
    private lateinit var modList: ModListWidget

    override fun init() {
        client!!.keyboard.setRepeatEvents(true)
        searchField = this.addSelectableChild(
            TextFieldWidget(
                textRenderer,
                10,
                10,
                100,
                20,
                TranslatableText("modmanager.search")
            )
        )
        searchField.setChangedListener { this.query = it }
        errorWidget = ErrorWidget(client!!, 10, 35, width - 130, height - 150)
        modList = addSelectableChild(ModListWidget(client!!, width - 130, height, 35, height - 10, 36))
        modList.setLeftPos(10)
        //TODO: Sorting selector
        //TODO: Paging
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (this.searchField.isFocused && keyCode == GLFW.GLFW_KEY_ENTER) {
            val provider = ModManager.modManager.getSelectedProvider() ?: return true
            return when (val result = provider.search(this.query, 0, 20)) {
                is ModsResult.Error -> {
                    this.errorWidget.error = result.text
                    true
                }
                is ModsResult.Success -> {
                    this.errorWidget.error = null
                    this.modList.setMods(result.mods)
                    true
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun tick() {
        super.tick()
        this.searchField.tick()
    }

    override fun render(matrices: MatrixStack?, mouseX: Int, mouseY: Int, delta: Float) {
        this.renderBackground(matrices)
        this.errorWidget.render(matrices, mouseX, mouseY, delta)
        if (this.errorWidget.error == null) {
            this.modList.render(matrices, mouseX, mouseY, delta)
        }
        this.searchField.render(matrices, mouseX, mouseY, delta)
        super.render(matrices, mouseX, mouseY, delta)
    }

    override fun onClose() {
        super.onClose()
        ModManager.modManager.icons.destroyAll()
        client!!.setScreen(previousScreen)
    }
}