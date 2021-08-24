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

package xyz.deathsgun.modmanager.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.terraformersmc.modmenu.util.DrawingUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ScreenTexts;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
import xyz.deathsgun.modmanager.ModManager;
import xyz.deathsgun.modmanager.api.manipulation.ManipulationTask;
import xyz.deathsgun.modmanager.api.mod.Category;
import xyz.deathsgun.modmanager.api.mod.DetailedMod;
import xyz.deathsgun.modmanager.api.mod.SummarizedMod;
import xyz.deathsgun.modmanager.gui.widget.DescriptionWidget;

import java.util.Objects;
import java.util.regex.Pattern;

import static xyz.deathsgun.modmanager.gui.widget.ModListEntry.LOADING_ICON;
import static xyz.deathsgun.modmanager.gui.widget.ModListEntry.UNKNOWN_ICON;

public class ModDetailScreen extends Screen {

    private static final Pattern HTML_PATTERN = Pattern.compile("<.*?>.*?</.*?>|</*.?>");
    final SummarizedMod summarizedMod;
    private final Screen previousScreen;
    private ButtonWidget actionButton;
    private DetailedMod detailedMod;
    private DescriptionWidget descriptionWidget;
    Exception exception;

    public ModDetailScreen(Screen previousScreen, SummarizedMod mod) {
        super(new LiteralText(mod.name()));
        this.previousScreen = previousScreen;
        this.summarizedMod = mod;
    }

    @Override
    protected void init() {
        super.init();
        try {
            detailedMod = ModManager.getModProvider().getMod(summarizedMod.id());
        } catch (Exception e) {
            e.printStackTrace();
            Objects.requireNonNull(this.client).setScreen(new ModManagerErrorScreen(previousScreen, e));
            return;
        }
        int buttonX = this.width / 8;
        String text = detailedMod.body().replaceAll("\\u00A0", " ");
        if (HTML_PATTERN.matcher(text).find()) {
            text = detailedMod.description();
        }
        this.descriptionWidget = this.addSelectableChild(new DescriptionWidget(client, this.width - 20, this.height - 34, 79, this.height - 30, textRenderer.fontHeight, text));
        this.descriptionWidget.setLeftPos(10);
        this.addDrawableChild(new ButtonWidget(buttonX, this.height - 28, 150, 20, ScreenTexts.BACK, button -> Objects.requireNonNull(client).setScreen(previousScreen)));

        this.actionButton = this.addDrawableChild(new ButtonWidget(this.width - buttonX - 150, this.height - 28, 150, 20, new TranslatableText("modmanager.message.install"),
                this::handleActionClick));
        this.updateActionButton();
    }

    private void handleActionClick(ButtonWidget buttonWidget) {
        if (exception != null) {
            buttonWidget.active = true;
            MinecraftClient.getInstance().setScreen(new ModManagerErrorScreen(this, exception));
            return;
        }
        buttonWidget.active = false;
        switch (ModManager.getState(summarizedMod)) {
            case OUTDATED -> {
                buttonWidget.setMessage(new TranslatableText("modmanager.message.updating"));
                ModManager.getModManipulationManager().updateMod(summarizedMod, this::handleTaskResult);
            }
            case DOWNLOADABLE -> {
                buttonWidget.setMessage(new TranslatableText("modmanager.message.installing"));
                ModManager.getModManipulationManager().installMod(summarizedMod, this::handleTaskResult);
            }
            case INSTALLED -> {
                buttonWidget.setMessage(new TranslatableText("modmanager.message.removing"));
                ModManager.getModManipulationManager().removeMod(summarizedMod, this::handleTaskResult);
            }
        }
    }

    private void handleTaskResult(ManipulationTask task) {
        this.exception = task.getException();
        updateActionButton();
    }

    void updateActionButton() {
        actionButton.active = true;
        if (exception != null) {
            actionButton.setMessage(new TranslatableText("modmanager.message.showError"));
            return;
        }
        switch (ModManager.getState(summarizedMod)) {
            case DOWNLOADABLE -> actionButton.setMessage(new TranslatableText("modmanager.message.install"));
            case INSTALLED -> actionButton.setMessage(new TranslatableText("modmanager.message.remove"));
            case OUTDATED -> actionButton.setMessage(new TranslatableText("modmanager.message.update"));
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        this.descriptionWidget.render(matrices, mouseX, mouseY, delta);

        int iconSize = 64;
        this.bindIconTexture();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        this.bindIconTexture();
        RenderSystem.enableBlend();
        DrawableHelper.drawTexture(matrices, 20, 10, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize);
        RenderSystem.disableBlend();

        final TextRenderer font = Objects.requireNonNull(client).textRenderer;

        MutableText trimmedTitle = new LiteralText(font.trimToWidth(detailedMod.name(), this.width - 200));
        trimmedTitle = trimmedTitle.setStyle(Style.EMPTY.withBold(true));

        int detailsY = 15;
        int textX = 20 + iconSize + 5;

        font.draw(matrices, trimmedTitle, textX, detailsY, 0xFFFFFF);

        font.draw(matrices, new TranslatableText("modmanager.details.author", summarizedMod.author()), textX, detailsY += 12, 0xFFFFFF);

        DrawingUtil.drawBadge(matrices, textX, detailsY += 12, font.getWidth(detailedMod.license()) + 6, Text.of(detailedMod.license()).asOrderedText(), 0xff6f6c6a, 0xff31302f, 0xCACACA);

        for (Category category : detailedMod.categories()) {
            int textWidth = font.getWidth(category.text()) + 6;
            DrawingUtil.drawBadge(matrices, textX, detailsY + 14, textWidth, category.text().asOrderedText(), 0xff6f6c6a, 0xff31302f, 0xCACACA);
            textX += textWidth + 4;
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    private void bindIconTexture() {
        if (ModManager.getIconManager().isErrored(summarizedMod.id())) {
            RenderSystem.setShaderTexture(0, UNKNOWN_ICON);
            return;
        }
        Identifier icon = ModManager.getIconManager().getIconByModId(summarizedMod.id());
        if (icon == null) {
            if (ModManager.getIconManager().isLoading(summarizedMod.id())) {
                icon = LOADING_ICON;
            } else {
                ModManager.getIconManager().downloadIcon(summarizedMod);
                return;
            }
        }
        RenderSystem.setShaderTexture(0, icon);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        Objects.requireNonNull(this.client).setScreen(previousScreen);
    }
}
