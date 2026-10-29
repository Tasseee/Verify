package dev.dtap;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Dark, pixel-inspired DTap settings screen with real Minecraft item icons. */
public class DTapScreen extends Screen {
    private static final int PURPLE = 0xFFB45CFF;
    private static final int PANEL = 0xE9101018;
    private static final int BORDER = 0xFF494052;
    private static final int GREEN = 0xFF45E56B;
    private static final int RED = 0xFFFF5555;
    private static final List<Item> ITEMS = List.of(
        Items.NETHERITE_INGOT, Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL, Items.DIAMOND,
        Items.GOLD_INGOT, Items.EMERALD, Items.ENCHANTED_GOLDEN_APPLE, Items.NETHERITE_SWORD,
        Items.SHULKER_BOX, Items.ELYTRA, Items.DRAGON_HEAD, Items.BEACON,
        Items.NETHERITE_CHESTPLATE, Items.TRIDENT, Items.CROSSBOW, Items.END_CRYSTAL
    );
    private int gridX, gridY, cellW = 37, cellH = 45;
    private String status = "Klicke ein Item, um es fuer Bodenschutz zu markieren.";
    private TextFieldWidget search;
    private boolean selfProtect = true;
    private boolean groundProtect = true;

    public DTapScreen() { super(Text.literal("D-Tap Einstellungen")); selfProtect = DTapClient.selfProtection; groundProtect = DTapClient.groundItemProtection; }

    @Override protected void init() {
        clearChildren();
        int cx = width / 2;
        int top = Math.max(38, height / 2 - 145);
        addDrawableChild(ButtonWidget.builder(Text.literal("SINGLE"), b -> setMode(1)).dimensions(cx - 151, top + 49, 96, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("DOUBLE"), b -> setMode(2)).dimensions(cx - 49, top + 49, 96, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("TRIPLE"), b -> setMode(3)).dimensions(cx + 53, top + 49, 96, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(selfProtect ? "Selbstschutz: AN" : "Selbstschutz: AUS"), b -> {
            selfProtect = !selfProtect; DTapClient.selfProtection = selfProtect; status = "Selbstschutz " + (selfProtect ? "aktiv" : "aus"); init();
        }).dimensions(cx - 151, top + 82, 148, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(groundProtect ? "Bodenitems: AN" : "Bodenitems: AUS"), b -> {
            groundProtect = !groundProtect; DTapClient.groundItemProtection = groundProtect; status = "Bodenitem-Schutz " + (groundProtect ? "aktiv" : "aus"); init();
        }).dimensions(cx + 5, top + 82, 148, 22).build());
        search = new TextFieldWidget(textRenderer, cx - 151, top + 115, 304, 20, Text.literal("Item suchen"));
        search.setPlaceholder(Text.literal("Suche nach Item-ID...")); search.setMaxLength(64); addDrawableChild(search);
        gridX = cx - 151; gridY = top + 143;
        addDrawableChild(ButtonWidget.builder(Text.literal("Alle auswaehlen"), b -> {
            for (Item item : ITEMS) DTapClient.groundBlockedItems.add(Registries.ITEM.getId(item).toString()); status = "Alle angezeigten Items blockiert";
        }).dimensions(cx - 151, top + 270, 148, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Auswahl loeschen"), b -> {
            for (Item item : ITEMS) DTapClient.groundBlockedItems.remove(Registries.ITEM.getId(item).toString()); status = "Auswahl entfernt";
        }).dimensions(cx + 5, top + 270, 148, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Fertig"), b -> close()).dimensions(cx - 60, top + 298, 120, 22).build());
    }

    private void setMode(int n) { DTapClient.desiredTaps = n; status = "Tap-Modus: " + (n == 1 ? "Single" : n == 2 ? "Double" : "Triple"); }

    @Override public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        int cx = width / 2, top = Math.max(38, height / 2 - 145);
        int left = cx - 164, right = cx + 164;
        ctx.fill(left, top - 23, right, top + 327, PANEL);
        ctx.fill(left, top - 23, right, top - 21, PURPLE);
        ctx.fill(left, top - 23, left + 2, top + 327, BORDER);
        ctx.fill(right - 2, top - 23, right, top + 327, BORDER);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("✦ D-TAP  |  AUTO CRYSTAL ✦"), cx, top - 14, PURPLE);
        ctx.drawTextWithShadow(textRenderer, Text.literal("TAP-MODUS"), cx - 151, top + 35, PURPLE);
        ctx.drawTextWithShadow(textRenderer, Text.literal("ITEM-BLOCKLISTE  ·  Items auf dem Boden"), cx - 151, top + 106, PURPLE);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Waehle Items, die vor Crystal-Explosionen geschuetzt werden."), cx - 151, top + 132, 0xFFCCCCCC);
        String query = search == null ? "" : search.getText().trim().toLowerCase();
        int i = 0;
        for (Item item : ITEMS) {
            String id = Registries.ITEM.getId(item).toString();
            if (!query.isEmpty() && !id.toLowerCase().contains(query) && !item.getName().getString().toLowerCase().contains(query)) continue;
            int col = i % 8, row = i / 8;
            int x = gridX + col * cellW, y = gridY + row * cellH;
            boolean selected = DTapClient.groundBlockedItems.contains(id);
            ctx.fill(x, y, x + cellW - 2, y + cellH - 2, selected ? 0xFF342044 : 0xFF17171F);
            ctx.fill(x, y, x + cellW - 2, y + 1, selected ? PURPLE : BORDER);
            ctx.fill(x, y + cellH - 3, x + cellW - 2, y + cellH - 2, BORDER);
            ctx.drawItem(new ItemStack(item), x + 10, y + 3);
            if (selected) ctx.drawTextWithShadow(textRenderer, Text.literal("✓"), x + cellW - 12, y + 1, GREEN);
            String name = item.getName().getString();
            if (name.length() > 8) name = name.substring(0, 7) + "…";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(name), x + (cellW - 2) / 2, y + 22, selected ? 0xFFE9D5FF : 0xFFDDDDDD);
            i++;
        }
        if (i == 0) ctx.drawTextWithShadow(textRenderer, Text.literal("Kein Item gefunden"), gridX, gridY + 18, RED);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Aktiv: " + DTapClient.desiredTaps + " Tap(s)   |   Bodenschutz-Items: " + DTapClient.groundBlockedItems.size()), cx, top + 251, 0xFFBDA5FF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, top + 321, 0xFFFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() != 0 || search == null) return false;
        String query = search.getText().trim().toLowerCase();
        int i = 0;
        for (Item item : ITEMS) {
            String id = Registries.ITEM.getId(item).toString();
            if (!query.isEmpty() && !id.toLowerCase().contains(query) && !item.getName().getString().toLowerCase().contains(query)) continue;
            int col = i % 8, row = i / 8;
            int x = gridX + col * cellW, y = gridY + row * cellH;
            if (mouseX >= x && mouseX < x + cellW - 2 && mouseY >= y && mouseY < y + cellH - 2) {
                if (!DTapClient.groundBlockedItems.remove(id)) DTapClient.groundBlockedItems.add(id);
                status = (DTapClient.groundBlockedItems.contains(id) ? "Bodenschutz aktiv: " : "Bodenschutz entfernt: ") + id;
                return true;
            }
            i++;
        }
        return false;
    }

    @Override public boolean shouldPause() { return false; }
}
