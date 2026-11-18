package dev.horizon.shaders.screen;

import dev.horizon.shaders.graph.NativePackRuntime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

final class NativePackSettingsScreen extends ShaderScreenBase {
    private final String packId;
    private PackSettingsModel model;
    private String status = "";
    private boolean advanced;
    private boolean error;
    private int page;
    private int pages;
    private int x;
    private int panelWidth;
    private int rows;
    private int columns;
    private final List<String> visible = new ArrayList<>();

    NativePackSettingsScreen(ShadersScreen parent, String packId) {
        super(parent, Component.literal("Shader settings"));
        this.packId = packId;

        try {
            this.model = new PackSettingsModel(NativePackRuntime.settings(packId));
        } catch (Exception failure) {
            this.status = "Could not load settings: " + failure.getMessage();
            this.error = true;
        }
    }

    protected void init() {
        this.clearWidgets();
        this.visible.clear();
        this.panelWidth = Math.min(720, this.width - 24);
        this.x = (this.width - this.panelWidth) / 2;
        this.columns = this.panelWidth >= 560 ? 2 : 1;
        this.rows = Math.max(1, (this.height - 156) / 36);
        List<String> keys = this.model == null ? List.of() : this.model.keys(this.advanced);
        int count = this.rows * this.columns;
        this.pages = Math.max(1, (keys.size() + count - 1) / count);
        this.page = Math.min(this.page, this.pages - 1);
        this.tab("Basic", false, this.x);
        this.tab("Advanced", true, this.x + this.panelWidth / 2 + 3);
        int cell = (this.panelWidth - 12 * (this.columns - 1)) / this.columns;

        for (int i = this.page * count; i < Math.min(keys.size(), (this.page + 1) * count); i++) {
            String key = keys.get(i);
            this.visible.add(key);
            int local = i - this.page * count;
            int left = this.x + local % this.columns * (cell + 12);
            int top = 76 + local / this.columns * 36;
            String label = this.model.name(key) + ": " + this.model.value(key, this.advanced);
            Button button = Button.builder(Component.literal(label), b -> {
                this.model.change(key, this.advanced);
                this.status = "";
                this.error = false;
                this.init();
            }).bounds(left, top, cell, 20).tooltip(Tooltip.create(Component.literal(this.model.help(key)))).build();
            button.active = this.model.available(key);
            this.addRenderableWidget(button);
        }

        if (this.pages > 1) {
            ((Button)this.addRenderableWidget(Button.builder(Component.literal("Previous"), b -> {
                this.page--;
                this.init();
            }).bounds(this.x, this.height - 76, 80, 20).build())).active = this.page > 0;
            ((Button)this.addRenderableWidget(Button.builder(Component.literal("Next"), b -> {
                this.page++;
                this.init();
            }).bounds(this.x + this.panelWidth - 80, this.height - 76, 80, 20).build())).active = this.page < this.pages - 1;
        }

        int third = (this.panelWidth - 12) / 3;
        ((Button)this.addRenderableWidget(
                Button.builder(Component.literal("Reset defaults"), b -> {
                        this.model.reset();
                        this.status = "Defaults restored. Press Apply to save.";
                        this.error = false;
                        this.init();
                    })
                    .bounds(this.x, this.height - 30, third, 20)
                    .tooltip(Tooltip.create(Component.literal("Reset all Basic and Advanced settings. Nothing is saved until Apply.")))
                    .build()
            ))
            .active = this.model != null;
        ((Button)this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
            try {
                NativePackRuntime.applyOptions(this.packId, this.model.values);
                this.model.applied();
                this.status = "Settings saved.";
                this.error = false;
            } catch (Exception failure) {
                this.status = "Could not apply: " + failure.getMessage();
                this.error = true;
            }

            this.init();
        }).bounds(this.x + third + 6, this.height - 30, third, 20).build())).active = this.model != null && this.model.dirty();
        this.addRenderableWidget(
            Button.builder(Component.literal(this.model != null && this.model.dirty() ? "Cancel" : "Done"), b -> this.onClose())
                .bounds(this.x + 2 * (third + 6), this.height - 30, third, 20)
                .tooltip(Tooltip.create(Component.literal("Close without saving unapplied changes.")))
                .build()
        );
    }

    private void tab(String label, boolean target, int left) {
        ((Button)this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            this.advanced = target;
            this.page = 0;
            this.init();
        }).bounds(left, 46, this.panelWidth / 2 - 3, 20).build())).active = this.advanced != target;
    }

    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.fillPanel(graphics, this.x - 8, 8, this.panelWidth + 16, this.height - 12);
        graphics.text(this.font, this.title, this.x, 18, -591621);
        String subtitle = this.advanced ? "Fine-tune quality and appearance" : "Choose your effects, quality and color style.";
        graphics.text(this.font, this.font.plainSubstrByWidth(subtitle, this.panelWidth), this.x, 32, -4734258);
        int cell = (this.panelWidth - 12 * (this.columns - 1)) / this.columns;

        for (int i = 0; i < this.visible.size(); i++) {
            String key = this.visible.get(i);
            int left = this.x + i % this.columns * (cell + 12);
            int top = 76 + i / this.columns * 36;
            String detail = !this.model.available(key)
                ? "Enable enhanced water first"
                : (this.advanced ? this.model.group(key) : this.model.help(key).split("\\.")[0]);
            graphics.text(this.font, this.font.plainSubstrByWidth(detail, cell - 4), left + 2, top + 23, -7694426);
        }

        if (this.pages > 1) {
            graphics.text(this.font, Component.literal(this.page + 1 + " / " + this.pages), this.x + this.panelWidth / 2 - 12, this.height - 70, -4734258);
        }

        String message = this.status.isEmpty()
            ? (this.model != null && this.model.dirty() ? "Unsaved changes" : "Changes apply only when you press Apply.")
            : this.status;
        graphics.text(this.font, this.font.plainSubstrByWidth(message, this.panelWidth), this.x, this.height - 45, this.error ? -809306 : -4734258);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
