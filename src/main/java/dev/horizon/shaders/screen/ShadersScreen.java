package dev.horizon.shaders.screen;

import com.mojang.blaze3d.Blaze3D;
import dev.horizon.shaders.config.ShaderConfig;
import dev.horizon.shaders.graph.NativePackRuntime;
import dev.horizon.shaders.pack.ShaderPackScanner;
import dev.horizon.shaders.runtime.BackendStatus;
import dev.horizon.shaders.runtime.ShaderRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.ObjectSelectionList.Entry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class ShadersScreen extends ShaderScreenBase {
    private static final String BUILTIN_PACK_NAME = "Sulkan Realistic";
    private static final Component BUILTIN_PACK_DESCRIPTION = Component.literal(
        "Default Vulkan shader. Clouds, reflections, atmospheric lighting and player shadows."
    );
    private static final Component TITLE = Component.literal("Shaders");
    private static final Component SUBTITLE = Component.translatable("sulkan.shaders.compatibility");
    private static final Component COMPACT_SUBTITLE = Component.translatable("sulkan.shaders.compatibility_short");
    private static final int MAX_CONTENT_WIDTH = 980;
    private static final int PANEL_GAP = 14;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;
    private ShadersScreen.PackList packList;
    private Button toggleButton;
    private Button reloadButton;
    private Button settingsButton;
    private Button openFolderButton;
    private Button refreshButton;
    private Button doneButton;
    private boolean shadersEnabled;
    private String selectedPackId;
    private boolean nativePackSelected;
    private boolean busy;
    private boolean statusError;
    private Component status = Component.empty();
    private List<ShaderPackScanner.AvailableShaderPack> packs = List.of();
    private List<ShaderPackScanner.UnsupportedShaderPack> unsupportedPacks = List.of();

    public ShadersScreen(Screen lastScreen) {
        super(lastScreen, TITLE);
        this.refreshState();
        this.resetStatus();
    }

    protected void init() {
        this.clearWidgets();
        this.refreshState();
        this.packList = (ShadersScreen.PackList)this.addRenderableWidget(new ShadersScreen.PackList());
        this.toggleButton = (Button)this.addRenderableWidget(Button.builder(this.toggleLabel(), button -> this.toggleShaders()).build());
        this.reloadButton = (Button)this.addRenderableWidget(
            Button.builder(
                    Component.literal("Reload shaders"),
                    button -> this.applySelection(true, Component.literal("Reloading " + this.packLabelText(this.selectedPackId) + "..."))
                )
                .build()
        );
        this.settingsButton = (Button)this.addRenderableWidget(
            Button.builder(Component.literal("Settings"), button -> this.minecraft.setScreenAndShow(new NativePackSettingsScreen(this, this.selectedPackId)))
                .build()
        );
        this.openFolderButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("Open folder"), button -> this.openShaderFolder()).build());
        this.refreshButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> this.refreshFolder()).build());
        this.doneButton = (Button)this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());
        this.refreshPackEntries();
        this.repositionElements();
        this.updateInteractivity();
        this.setInitialFocus(this.packList);
    }

    public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
        ShadersScreen.Layout layout = this.layout();
        this.fillPanel(guiGraphicsExtractor, layout.listX, layout.listY, layout.listWidth, layout.listHeight);
        if (!layout.compact) {
            this.fillPanel(guiGraphicsExtractor, layout.sideX, layout.sideY, layout.sideWidth, layout.sideHeight);
        }

        guiGraphicsExtractor.text(this.font, TITLE, layout.listX + 16, layout.listY + 14, -591621, true);
        guiGraphicsExtractor.textWithWordWrap(
            this.font, layout.compact ? COMPACT_SUBTITLE : SUBTITLE, layout.listX + 16, layout.listY + 30, layout.listWidth - 32, -4734258
        );
        if (!layout.compact) {
            int sideInnerX = layout.sideX + 14;
            guiGraphicsExtractor.text(this.font, Component.literal("Actions"), sideInnerX, layout.sideY + 14, -591621, true);
            boolean nativePack = this.nativePackSelected;
            guiGraphicsExtractor.text(
                this.font, Component.literal(this.shadersEnabled ? "Shaders are on" : "Shaders are off"), sideInnerX, layout.sideY + 36, -4734258
            );
            guiGraphicsExtractor.text(
                this.font, Component.literal(nativePack ? "Pack controls available" : "Built-in effects"), sideInnerX, layout.sideY + 50, -4734258
            );
            guiGraphicsExtractor.text(this.font, Component.literal("Settings: Basic / Advanced"), sideInnerX, layout.sideY + 66, -7694426);
        }

        int statusColor = this.statusError ? -809306 : -5839967;
        int statusY = layout.compact ? this.compactStatusY(layout) : layout.listY + layout.listHeight - 34;
        guiGraphicsExtractor.textWithWordWrap(this.font, this.status, layout.listX + 16, statusY, layout.listWidth - 32, statusColor);
        if (!layout.compact && !this.unsupportedPacks.isEmpty()) {
            guiGraphicsExtractor.text(
                this.font,
                Component.literal("Iris/OptiFine packs hidden: " + this.unsupportedPacks.size()),
                layout.listX + 16,
                layout.listY + layout.listHeight - 48,
                -809306
            );
        }

        super.extractRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);
    }

    void refreshFromSettings(Component message, boolean error) {
        this.refreshState();
        this.status = message;
        this.statusError = error;
        if (this.packList != null) {
            this.refreshPackEntries();
            this.repositionElements();
            this.updateInteractivity();
        }
    }

    protected void repositionElements() {
        if (this.packList != null) {
            ShadersScreen.Layout layout = this.layout();
            this.updateButtonLabels(layout.compact);
            if (layout.compact) {
                this.repositionCompact(layout);
            } else {
                int listTop = layout.listY + 58;
                int listBottom = layout.listY + layout.listHeight - 56;
                this.packList.updateSizeAndPosition(layout.listWidth - 24, Math.max(24, listBottom - listTop), layout.listX + 12, listTop);
                int buttonX = layout.sideX + 14;
                int buttonWidth = layout.sideWidth - 28;
                int y = layout.sideY + 92;
                this.toggleButton.setRectangle(buttonWidth, 20, buttonX, y);
                y += 28;
                this.reloadButton.setRectangle(buttonWidth, 20, buttonX, y);
                y += 28;
                this.settingsButton.setRectangle(buttonWidth, 20, buttonX, y);
                y += 28;
                int halfWidth = (buttonWidth - 8) / 2;
                this.openFolderButton.setRectangle(halfWidth, 20, buttonX, y);
                this.refreshButton.setRectangle(halfWidth, 20, buttonX + halfWidth + 8, y);
                this.doneButton.setRectangle(buttonWidth, 20, buttonX, layout.sideY + layout.sideHeight - 30);
            }
        }
    }

    private void repositionCompact(ShadersScreen.Layout layout) {
        int innerX = layout.listX + 12;
        int innerWidth = Math.max(1, layout.listWidth - 24);
        int columns = innerWidth >= 300 ? 3 : 2;
        int rows = (6 + columns - 1) / columns;
        int gap = 5;
        int actionsHeight = rows * 20 + (rows - 1) * gap;
        int actionsY = layout.listY + layout.listHeight - 10 - actionsHeight;
        int statusY = actionsY - 18;
        int listTop = layout.listY + 48;
        int listBottom = statusY - 4;
        this.packList.updateSizeAndPosition(innerWidth, Math.max(24, listBottom - listTop), innerX, listTop);
        Button[] buttons = new Button[]{this.toggleButton, this.reloadButton, this.settingsButton, this.openFolderButton, this.refreshButton, this.doneButton};
        int buttonWidth = Math.max(1, (innerWidth - (columns - 1) * gap) / columns);

        for (int i = 0; i < buttons.length; i++) {
            int column = i % columns;
            int row = i / columns;
            buttons[i].setRectangle(buttonWidth, 20, innerX + column * (buttonWidth + gap), actionsY + row * (20 + gap));
        }
    }

    private int compactStatusY(ShadersScreen.Layout layout) {
        int innerWidth = Math.max(1, layout.listWidth - 24);
        int columns = innerWidth >= 300 ? 3 : 2;
        int rows = (6 + columns - 1) / columns;
        int actionsHeight = rows * 20 + (rows - 1) * 5;
        return layout.listY + layout.listHeight - 10 - actionsHeight - 18;
    }

    private ShadersScreen.Layout layout() {
        int margin = this.width >= 520 && this.height >= 320 ? 18 : 6;
        int contentWidth = Math.max(1, Math.min(this.width - margin * 2, 980));
        int contentHeight = Math.max(1, this.height - margin * 2);
        int contentX = (this.width - contentWidth) / 2;
        int contentY = margin;
        boolean compact = contentWidth < 760 || contentHeight < 260;
        if (compact) {
            return new ShadersScreen.Layout(contentX, contentY, contentWidth, contentHeight, contentX, contentY, contentWidth, contentHeight, true);
        }

        int sideWidth = Math.min(260, Math.max(220, contentWidth / 3));
        int listWidth = contentWidth - sideWidth - 14;
        return new ShadersScreen.Layout(contentX, contentY, listWidth, contentHeight, contentX + listWidth + 14, contentY, sideWidth, contentHeight, false);
    }

    private void refreshState() {
        ShaderPackScanner.ScanResult scan = ShaderRuntime.reloadPacks();
        this.packs = scan.supportedPacks();
        this.unsupportedPacks = scan.unsupportedPacks();
        ShaderConfig config = ShaderRuntime.config();
        this.shadersEnabled = config.enabled();
        this.selectedPackId = config.selectedPackId();
        if (!ShaderPackScanner.isKnownPackId(this.selectedPackId, this.packs)) {
            this.selectedPackId = "__builtin__";
        }

        this.nativePackSelected = NativePackRuntime.selected(config);
    }

    private void refreshPackEntries() {
        List<ShadersScreen.PackEntry> entries = new ArrayList<>();
        ShadersScreen.PackEntry selectedEntry = null;
        ShadersScreen.PackEntry builtinEntry = new ShadersScreen.PackEntry("__builtin__", Component.literal("Sulkan Realistic"), BUILTIN_PACK_DESCRIPTION);
        entries.add(builtinEntry);
        if (Objects.equals(this.selectedPackId, "__builtin__")) {
            selectedEntry = builtinEntry;
        }

        for (ShaderPackScanner.AvailableShaderPack pack : this.packs) {
            ShadersScreen.PackEntry entry = new ShadersScreen.PackEntry(
                pack.id(), Component.literal(pack.displayName()), Component.literal("External shader pack")
            );
            entries.add(entry);
            if (Objects.equals(this.selectedPackId, pack.id())) {
                selectedEntry = entry;
            }
        }

        this.packList.replaceEntries(entries);
        if (selectedEntry != null) {
            this.packList.setSelected(selectedEntry);
        }
    }

    private void selectPack(String packId) {
        boolean alreadyLive = Objects.equals(this.selectedPackId, packId)
            && NativePackRuntime.failure() == null
            && Objects.equals(packId, ShaderRuntime.config().selectedPackId());
        if (!this.busy && !alreadyLive) {
            this.selectedPackId = packId;
            this.applySelection(false, Component.literal("Applying " + this.packLabelText(packId) + "..."));
        }
    }

    private void toggleShaders() {
        this.shadersEnabled = !this.shadersEnabled;
        this.updateButtonLabels(this.layout().compact);
        this.applySelection(false, Component.literal(this.shadersEnabled ? "Enabling shaders..." : "Disabling shaders..."));
    }

    private void applySelection(boolean forceReload, Component pendingStatus) {
        if (this.minecraft != null && !this.busy) {
            this.busy = true;
            this.statusError = false;
            this.status = pendingStatus;
            this.updateInteractivity();
            ShaderConfig nextConfig = ShaderRuntime.config().withSelection(this.shadersEnabled, this.selectedPackId);
            ShaderRuntime.applyConfig(this.minecraft, nextConfig, forceReload)
                .whenComplete(
                    (ignored, throwable) -> this.minecraft
                        .execute(
                            () -> {
                                this.busy = false;
                                this.refreshState();
                                this.refreshPackEntries();
                                this.updateInteractivity();
                                if (throwable != null) {
                                    this.statusError = true;
                                    this.status = Component.literal(
                                        "Reload failed while applying " + this.packLabelText(this.selectedPackId) + ". Check the log for shader errors."
                                    );
                                } else {
                                    this.statusError = false;
                                    this.status = forceReload
                                        ? Component.literal("Reloaded " + this.packLabelText(this.selectedPackId) + ".")
                                        : Component.literal("Applied " + (this.shadersEnabled ? this.packLabelText(this.selectedPackId) : "Off") + ".");
                                }
                            }
                        )
                );
        }
    }

    private void openShaderFolder() {
        Blaze3D.openPath(ShaderPackScanner.shaderPackDirectory());
        this.statusError = false;
        this.status = Component.literal("Opened the shader folder.");
    }

    private void refreshFolder() {
        this.refreshState();
        this.refreshPackEntries();
        this.statusError = false;
        this.status = this.unsupportedPacks.isEmpty()
            ? Component.literal("Shader folder refreshed.")
            : Component.literal("Shader folder refreshed. " + this.unsupportedPacks.size() + " Iris/OptiFine packs hidden.");
    }

    private void updateInteractivity() {
        boolean active = !this.busy && BackendStatus.vulkanActive();
        this.packList.active = active;
        this.toggleButton.active = active;
        this.reloadButton.active = active;
        this.settingsButton.active = active;
        this.refreshButton.active = active;
        this.openFolderButton.active = true;
        this.doneButton.active = true;
        this.updateButtonLabels(this.layout().compact);
    }

    private void resetStatus() {
        if (NativePackRuntime.failure() != null) {
            this.statusError = true;
            this.status = Component.literal(NativePackRuntime.failure());
        } else if (!BackendStatus.vulkanActive()) {
            this.statusError = true;
            this.status = Component.translatable("sulkan.backend.inactive_short");
        } else {
            this.statusError = false;
            this.status = this.shadersEnabled
                ? Component.literal("Ready. Live pack: " + this.packLabelText(this.selectedPackId))
                : Component.literal("Shaders are off. Select a pack now and enable when ready.");
        }
    }

    private Component toggleLabel() {
        return Component.literal(this.shadersEnabled ? "Disable shaders" : "Enable shaders");
    }

    private void updateButtonLabels(boolean compact) {
        this.toggleButton.setMessage((Component)(compact ? Component.literal(this.shadersEnabled ? "Disable" : "Enable") : this.toggleLabel()));
        this.reloadButton.setMessage(Component.literal(compact ? "Reload" : "Reload shaders"));
        this.openFolderButton.setMessage(Component.literal(compact ? "Folder" : "Open folder"));
    }

    private String packLabelText(String packId) {
        if ("__builtin__".equals(packId)) {
            return "Sulkan Realistic";
        } else {
            return packId.endsWith(".zip") ? packId.substring(0, packId.length() - 4) : packId;
        }
    }

    private record Layout(int listX, int listY, int listWidth, int listHeight, int sideX, int sideY, int sideWidth, int sideHeight, boolean compact) {
    }

    private final class PackEntry extends Entry<ShadersScreen.PackEntry> {
        private final String packId;
        private final Component name;
        private final Component type;

        private PackEntry(String packId, Component name, Component type) {
            this.packId = packId;
            this.name = name;
            this.type = type;
        }

        public Component getNarration() {
            return Component.literal(this.name.getString() + ", " + this.type.getString());
        }

        public void extractContent(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, boolean hovered, float partialTick) {
            boolean selected = Objects.equals(this.packId, ShadersScreen.this.selectedPackId);
            boolean live = ShaderRuntime.shadersEnabled()
                && NativePackRuntime.failure() == null
                && Objects.equals(this.packId, ShaderRuntime.config().selectedPackId());
            int backgroundColor = selected ? -266063036 : (hovered ? -870701775 : -1609294811);
            int borderColor = selected ? -5010871 : (hovered ? -10189404 : -12957092);
            guiGraphicsExtractor.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), backgroundColor);
            guiGraphicsExtractor.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), borderColor);
            guiGraphicsExtractor.text(ShadersScreen.this.font, this.name, this.getContentX() + 10, this.getContentY() + 7, -591621, true);
            guiGraphicsExtractor.text(ShadersScreen.this.font, this.type, this.getContentX() + 10, this.getContentY() + 22, -4734258);
            if (live || selected) {
                Component badge = Component.literal(live ? "LIVE" : "SELECTED");
                int badgeWidth = ShadersScreen.this.font.width(badge) + 12;
                int badgeX = this.getContentRight() - badgeWidth - 8;
                guiGraphicsExtractor.fill(badgeX, this.getContentY() + 8, badgeX + badgeWidth, this.getContentY() + 22, live ? -870894301 : -870305727);
                guiGraphicsExtractor.text(ShadersScreen.this.font, badge, badgeX + 6, this.getContentY() + 11, live ? -5839967 : -591621);
            }
        }

        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0 && !ShadersScreen.this.busy) {
                ShadersScreen.this.packList.setSelected(this);
                ShadersScreen.this.selectPack(this.packId);
                return true;
            } else {
                return false;
            }
        }
    }

    private final class PackList extends ObjectSelectionList<ShadersScreen.PackEntry> {
        private PackList() {
            super(ShadersScreen.this.minecraft, 0, 0, 0, 42);
        }

        public int getRowWidth() {
            return this.width - 12;
        }

        protected int scrollBarX() {
            return this.getX() + this.width - 6;
        }

        protected boolean entriesCanBeSelected() {
            return true;
        }

        protected void extractListBackground(GuiGraphicsExtractor guiGraphicsExtractor) {
        }

        protected void extractListSeparators(GuiGraphicsExtractor guiGraphicsExtractor) {
        }
    }
}
