package io.github.lollip0529.biscuits;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lollip0529.biscuits.Snapshot.BlockEntityInfo;
import io.github.lollip0529.biscuits.Snapshot.Edge;
import io.github.lollip0529.biscuits.Snapshot.EntityInfo;
import io.github.lollip0529.biscuits.Snapshot.Section;
import io.github.lollip0529.biscuits.Snapshot.Status;
import com.mojang.blaze3d.platform.GlDebugInfo;
import io.github.lollip0529.biscuits.mixin.DebugRendererAccessor;
import io.github.lollip0529.biscuits.mixin.MinecraftClientAccessor;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.profiler.ProfileResult;
import net.minecraft.util.profiler.ProfilerTiming;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// full screen map, laid out like xaero's world map. buttons, slider and tooltips are drawn by hand so they scale with
// the map instead of the gui scale
public class MapScreen extends Screen {
    private static final int BAR = 28, PANEL = 230;

    // Screen.renderTooltip colours
    private static final int TIP_BG = 0xF0100010, TIP_BORDER = 0x505000FF, TIP_BORDER_END = 0x5028007F;
    // Formatting colours
    private static final int WHITE = 0xFFFFFF, GRAY = 0xAAAAAA, DARK_GRAY = 0x555555, YELLOW = 0xFFFF55, GREEN = 0x55FF55, GOLD = 0xFFAA00;
    // corner markers, bright = drawn last frame
    private static final int SPAWNER = 0xFFFF55FF, SPAWNER_DIM = 0xFF7F2A7F;
    private static final int BLOCK_ENTITY = 0xFFFFFF55, BLOCK_ENTITY_DIM = 0xFF7F7F2A;
    private static final int ENTITY = 0xFF55FFFF, ENTITY_DIM = 0xFF2A7F7F;

    // kept between openings. cell 0 = fit the render distance
    private static double savedCell;
    private static boolean allY, terrainOn, frustumOn, legend, info, frozen;
    private static Snapshot savedLive;
    // pastry reads the pie from here, the slices under level > entities
    private static final String PIE = "root\u001egameRenderer\u001elevel\u001eentities";

    private Toolscreen toolscreen;
    private Toolscreen.Mode view;
    private Snapshot live = savedLive, sim, snap;
    private long captured;
    private boolean centered;
    private double viewX, viewZ, cell = savedCell;
    private int sliceY;
    private boolean followY = true;
    private boolean dragging, sliding;
    private boolean pinned;
    private int pinX, pinZ, scroll, infoScroll, infoRight;
    private Terrain terrain;

    // laid out at gui scale 2 max so there's still room for the map at scale 4
    private double ui = 1;
    private int cw, ch;
    private double mx = -1, my = -1;
    private int sliderX, sliderW;
    private final List<Hit> hits = new ArrayList<>();

    public MapScreen() {
        super(new LiteralText("biscuits"));
    }

    @Override
    protected void init() {
        double f = this.client.getWindow().getScaleFactor();
        this.ui = Math.min(f, 2) / f;
        this.cw = (int) (this.width / this.ui);
        this.ch = (int) (this.height / this.ui);
        if (this.toolscreen == null) this.toolscreen = Toolscreen.read();
    }

    @Override
    public void removed() {
        savedCell = this.cell;
        savedLive = frozen ? this.live : null;
        if (this.terrain != null) {
            this.terrain.close();
            this.terrain = null;
        }
    }

    private Snapshot shown() {
        if (this.view == null || this.live == null) return this.live;
        if (this.sim == null || this.sim.time != this.live.time || !this.sim.label.equals(this.view.id)) {
            this.sim = this.live.simulate(this.view.id, this.view.width, this.view.height);
        }
        return this.sim;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        // recapture every 100ms unless frozen
        if (this.live == null || !frozen && now - this.captured > 100) {
            Snapshot s = Snapshot.capture(this.client);
            if (s != null) {
                this.live = s;
                this.captured = now;
            }
        }
        this.snap = this.shown();
        this.mx = mouseX / this.ui;
        this.my = mouseY / this.ui;
        this.hits.clear();

        matrices.push();
        matrices.scale((float) this.ui, (float) this.ui, 1);
        fill(matrices, 0, 0, this.cw, this.ch, 0xFF000000);

        if (this.snap == null) {
            drawCenteredString(matrices, this.textRenderer, "Waiting for Sodium's chunk graph...", this.cw / 2, this.ch / 2, GRAY);
            matrices.pop();
            return;
        }
        if (!this.centered) {
            this.viewX = this.snap.camera.x / 16;
            this.viewZ = this.snap.camera.z / 16;
            this.centered = true;
        }
        // first open
        if (this.cell <= 0) {
            int span = 2 * this.snap.renderDistance + 3;
            this.cell = MathHelper.clamp(Math.min(this.cw, this.ch - BAR) / span, 4, 22);
        }
        if (this.followY) {
            this.sliceY = MathHelper.clamp(this.snap.centerY, 0, 15);
        }

        Matrix4f m = matrices.peek().getModel();
        if (terrainOn) {
            this.drawTerrain(m);
        }
        this.drawGrid(matrices, m);
        if (frustumOn) {
            this.drawFrustum(m);
        }
        this.drawCamera(m);
        this.drawInfo(matrices);
        this.drawButtons(matrices);
        if (legend) {
            this.drawLegend(matrices);
        }
        if (info) {
            this.drawInfoPanel(matrices);
        }
        if (this.pinned) {
            this.drawPanel(matrices);
        }
        if (!this.dragging && !this.sliding && this.overMap(this.mx, this.my)) {
            this.drawTooltip(matrices);
        }
        matrices.pop();
    }

    private void drawTerrain(Matrix4f m) {
        if (this.terrain == null) {
            this.terrain = new Terrain(this.client, this.snap.centerX, this.snap.centerZ, this.snap.renderDistance + 3);
        }
        this.terrain.update(allY ? Terrain.SURFACE : this.sliceY);

        this.client.getTextureManager().bindTexture(this.terrain.id);
        float x0 = this.sx(this.terrain.originX), y0 = this.sz(this.terrain.originZ);
        float x1 = this.sx(this.terrain.originX + this.terrain.size), y1 = this.sz(this.terrain.originZ + this.terrain.size);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.color4f(1, 1, 1, 1);
        BufferBuilder b = Tessellator.getInstance().getBuffer();
        b.begin(GL11.GL_QUADS, VertexFormats.POSITION_TEXTURE);
        b.vertex(m, x0, y1, 0).texture(0, 1).next();
        b.vertex(m, x1, y1, 0).texture(1, 1).next();
        b.vertex(m, x1, y0, 0).texture(1, 0).next();
        b.vertex(m, x0, y0, 0).texture(0, 0).next();
        Tessellator.getInstance().draw();
        RenderSystem.disableBlend();
    }

    private void drawGrid(MatrixStack matrices, Matrix4f m) {
        Snapshot s = this.snap;
        int r = s.renderDistance + 3;
        float gap = this.cell >= 6 ? 1 : 0;
        float mark = Math.max(2, (float) this.cell / 4);
        List<int[]> counts = new ArrayList<>();

        BufferBuilder b = begin();
        for (int x = s.centerX - r; x <= s.centerX + r; x++) {
            for (int z = s.centerZ - r; z <= s.centerZ + r; z++) {
                float x0 = this.sx(x), z0 = this.sz(z), x1 = this.sx(x + 1) - gap, z1 = this.sz(z + 1) - gap;
                if (x1 < 0 || x0 > this.cw || z1 < 0 || z0 > this.ch) continue;

                int color = this.status(x, z).color;
                quad(b, m, x0, z0, x1, z1, terrainOn ? color & 0xFFFFFF | 0x50000000 : color);

                // corner markers, only once the cells are big enough
                if (this.cell < 8) continue;
                int be = 0, beDrawn = 0, ent = 0, entDrawn = 0;
                boolean spawner = false;
                for (Section sec : this.column(x, z)) {
                    for (BlockEntityInfo info : sec.blockEntities) {
                        if (!info.renderer) continue;
                        be++;
                        if (info.drawn) beDrawn++;
                        spawner |= info.mob != null;
                    }
                    for (EntityInfo e : sec.entities) {
                        if (e.self) continue;
                        ent++;
                        if (e.drawn) entDrawn++;
                    }
                }
                if (be > 0) {
                    int c = spawner ? (beDrawn > 0 ? SPAWNER : SPAWNER_DIM) : (beDrawn > 0 ? BLOCK_ENTITY : BLOCK_ENTITY_DIM);
                    quad(b, m, x0 + 1, z0 + 1, x0 + 1 + mark, z0 + 1 + mark, c);
                }
                if (ent > 0) {
                    quad(b, m, x1 - 1 - mark, z0 + 1, x1 - 1, z0 + 1 + mark, entDrawn > 0 ? ENTITY : ENTITY_DIM);
                    if (this.cell >= 28) counts.add(new int[]{(int) x1, (int) z1, ent});
                }
            }
        }

        // camera, hover and pinned outlines
        int camX = MathHelper.floor(s.camera.x) >> 4, camZ = MathHelper.floor(s.camera.z) >> 4;
        outline(b, m, this.sx(camX), this.sz(camZ), this.sx(camX + 1) - gap, this.sz(camZ + 1) - gap, 0x90FFFFFF);
        if (!this.dragging && !this.sliding && this.overMap(this.mx, this.my)) {
            int hx = this.chunkX(this.mx), hz = this.chunkZ(this.my);
            outline(b, m, this.sx(hx), this.sz(hz), this.sx(hx + 1) - gap, this.sz(hz + 1) - gap, 0xC0FFFFFF);
        }
        if (this.pinned) {
            float x0 = this.sx(this.pinX), z0 = this.sz(this.pinZ), x1 = this.sx(this.pinX + 1) - gap, z1 = this.sz(this.pinZ + 1) - gap;
            outline(b, m, x0 - 1, z0 - 1, x1 + 1, z1 + 1, 0xFFFFFFFF);
            outline(b, m, x0 - 2, z0 - 2, x1 + 2, z1 + 2, 0xFFFFFFFF);
        }
        end();

        // entity counts when zoomed in
        for (int[] c : counts) {
            String n = String.valueOf(c[2]);
            this.textRenderer.drawWithShadow(matrices, n, c[0] - 2 - this.textRenderer.getWidth(n), c[1] - 10, ENTITY);
        }
    }

    // search frustum for this slab, clipped to the grid
    private void drawFrustum(Matrix4f m) {
        int y0 = allY ? 0 : this.sliceY << 4;
        List<double[]> poly = this.snap.frustum(y0, allY ? 256 : y0 + 16);
        if (poly.size() < 3) return;
        int r = this.snap.renderDistance + 3;
        this.scissor((int) this.sx(this.snap.centerX - r), (int) this.sz(this.snap.centerZ - r),
                (int) this.sx(this.snap.centerX + r + 1), (int) this.sz(this.snap.centerZ + r + 1));

        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // z is flipped on screen -> hull winds backwards
        RenderSystem.disableCull();
        BufferBuilder b = Tessellator.getInstance().getBuffer();
        b.begin(GL11.GL_TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
        for (double[] p : poly) b.vertex(m, this.sx(p[0] / 16), this.sz(p[1] / 16), 0).color(1f, 1f, 1f, 0.25f).next();
        Tessellator.getInstance().draw();
        RenderSystem.enableCull();
        RenderSystem.lineWidth(2);
        b.begin(GL11.GL_LINE_LOOP, VertexFormats.POSITION_COLOR);
        for (double[] p : poly) b.vertex(m, this.sx(p[0] / 16), this.sz(p[1] / 16), 0).color(1f, 1f, 1f, 0.9f).next();
        Tessellator.getInstance().draw();
        RenderSystem.lineWidth(1);
        this.noScissor();
        end();
    }

    private void drawCamera(Matrix4f m) {
        Snapshot s = this.snap;
        float px = this.sx(s.camera.x / 16), pz = this.sz(s.camera.z / 16);
        double yaw = Math.toRadians(s.yaw);
        double half = s.horizontalHalfFov();
        float len = (float) ((s.renderDistance + 1) * this.cell);

        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(2);
        BufferBuilder b = Tessellator.getInstance().getBuffer();
        b.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
        // fov lines
        for (double a : new double[]{yaw - half, yaw + half}) {
            b.vertex(m, px, pz, 0).color(1f, 1f, 1f, 0.75f).next();
            b.vertex(m, px - (float) Math.sin(a) * len, pz + (float) Math.cos(a) * len, 0).color(1f, 1f, 1f, 0.05f).next();
        }
        Tessellator.getInstance().draw();
        RenderSystem.lineWidth(1);

        // player arrow, white with a dark rim like xaero's
        float dx = (float) -Math.sin(yaw), dz = (float) Math.cos(yaw);
        b.begin(GL11.GL_TRIANGLES, VertexFormats.POSITION_COLOR);
        arrow(b, m, px, pz, dx, dz, 9, 0f);
        arrow(b, m, px, pz, dx, dz, 7, 1f);
        Tessellator.getInstance().draw();
        end();
    }

    private static void arrow(BufferBuilder b, Matrix4f m, float px, float pz, float dx, float dz, float size, float shade) {
        b.vertex(m, px + dx * size, pz + dz * size, 0).color(shade, shade, shade, 1f).next();
        b.vertex(m, px - dx * size * 0.6f + dz * size * 0.6f, pz - dz * size * 0.6f - dx * size * 0.6f, 0).color(shade, shade, shade, 1f).next();
        b.vertex(m, px - dx * size * 0.6f - dz * size * 0.6f, pz - dz * size * 0.6f + dx * size * 0.6f, 0).color(shade, shade, shade, 1f).next();
    }

    // top left text, where xaero has its coords
    private void drawInfo(MatrixStack matrices) {
        Snapshot s = this.snap;
        int y = 6;
        if (this.view == null) {
            this.textRenderer.drawWithShadow(matrices, "Live", 6, y, WHITE);
        } else {
            String name = this.view.id + " " + this.view.width + "×" + this.view.height;
            this.textRenderer.drawWithShadow(matrices, name, 6, y, YELLOW);
            this.textRenderer.drawWithShadow(matrices, "  replayed from the live search", 6 + this.textRenderer.getWidth(name), y, GRAY);
        }
        List<String> lines = new ArrayList<>();
        lines.add(s.visible + " sections reached, " + s.drawn + " drawn");
        Map<String, Double> pie = this.pie();
        if (pie == null) {
            lines.add("Pie chart off (Shift + F3)");
        } else {
            StringBuilder sb = new StringBuilder(this.view == null ? "Pie:" : "Live pie:");
            for (String slice : new String[]{"blockentities", "entities", "unspecified"}) {
                if (pie.containsKey(slice)) sb.append(String.format(" %s %.1f%%", slice, pie.get(slice)));
            }
            lines.add(sb.toString());
        }
        lines.add("Block entities: " + total(s.drawnBlockEntities) + list(s.drawnBlockEntities));
        lines.add("Entities: " + total(s.drawnEntities) + "/" + this.client.world.getRegularEntityCount() + list(s.drawnEntities));
        BlockPos outline = Biscuits.drawnOutline();
        lines.add("Block outline: " + (outline == null ? "No" : "Yes (" + outline.getX() + " " + outline.getY() + " " + outline.getZ() + ")"));
        y += 11;
        for (String line : lines) {
            this.textRenderer.drawWithShadow(matrices, line, 6, y, GRAY);
            y += 10;
        }
        if (frozen) {
            this.textRenderer.drawWithShadow(matrices, "Frozen", 6, y + 1, GOLD);
        }
    }

    // parent % of the pie slices, null if the pie is off
    private Map<String, Double> pie() {
        ProfileResult result = ((MinecraftClientAccessor) this.client).biscuits$pie();
        if (result == null) return null;
        Map<String, Double> out = new LinkedHashMap<>();
        for (ProfilerTiming t : result.getTimings(PIE)) {
            if (t.name.indexOf('\u001e') < 0) out.put(t.name, t.parentSectionUsagePercentage);
        }
        return out.isEmpty() ? null : out;
    }

    // info panel
    private List<Line> info() {
        List<Line> out = new ArrayList<>();
        Snapshot s = this.snap;
        out.add(new Line(Line.Kind.HEAD, "Frame", YELLOW));
        out.add(row("Camera", String.format("%.2f %.2f %.2f", s.camera.x, s.camera.y, s.camera.z)));
        out.add(row("Yaw / pitch", String.format("%.1f / %.1f", s.yaw, s.pitch)));
        BlockPos outline = Biscuits.drawnOutline();
        out.add(row("Block outline", outline == null ? "no" : outline.getX() + " " + outline.getY() + " " + outline.getZ()));
        out.add(row("Entities in world", String.valueOf(this.client.world.getRegularEntityCount())));

        out.add(new Line(Line.Kind.HEAD, "Pie chart", YELLOW).right("of level > entities", GRAY));
        ProfileResult result = ((MinecraftClientAccessor) this.client).biscuits$pie();
        if (result == null) {
            out.add(new Line(Line.Kind.TEXT, "off, open it with Shift + F3", DARK_GRAY));
        } else {
            for (ProfilerTiming t : result.getTimings(PIE)) {
                if (t.name.indexOf('\u001e') >= 0) continue;
                out.add(row(t.name, String.format("%.1f%%  (%.2f%% total)", t.parentSectionUsagePercentage, t.totalUsagePercentage)));
            }
        }

        GameOptions o = this.client.options;
        out.add(new Line(Line.Kind.HEAD, "Settings", YELLOW));
        out.add(row("Render distance", o.viewDistance + " chunks"));
        out.add(row("Entity distance", Math.round(o.entityDistanceScaling * 100) + "%"));
        out.add(row("FOV", String.valueOf((int) o.fov)));
        out.add(row("Window", this.client.getWindow().getFramebufferWidth() + "x" + this.client.getWindow().getFramebufferHeight()));
        out.add(row("Hitboxes", this.client.getEntityRenderManager().shouldRenderHitboxes() ? "on" : "off"));
        out.add(row("Chunk borders", ((DebugRendererAccessor) this.client.debugRenderer).biscuits$chunkBorders() ? "on" : "off"));

        out.add(new Line(Line.Kind.HEAD, "World", YELLOW));
        out.add(row("Dimension", this.client.world.getRegistryKey().getValue().getPath()));
        out.add(row("Gamemode", this.client.interactionManager.getCurrentGameMode().getName()));
        if (this.client.getServer() != null) {
            out.add(row("World", this.client.getServer().getSaveProperties().getLevelName()));
            out.add(row("Seed", String.valueOf(this.client.getServer().getSaveProperties().getGeneratorOptions().getSeed())));
        }

        out.add(new Line(Line.Kind.HEAD, "System", YELLOW));
        out.add(row("GL", GlDebugInfo.getRenderer()));
        out.add(row("GL version", GlDebugInfo.getVersion()));
        out.add(row("GL vendor", GlDebugInfo.getVendor()));
        out.add(row("CPU", GlDebugInfo.getCpuInfo()));
        out.add(row("Java", System.getProperty("java.version")));
        out.add(row("Memory", Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB"));
        out.add(row("Processors", String.valueOf(Runtime.getRuntime().availableProcessors())));
        out.add(row("JVM args", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments())));
        return out;
    }

    private void drawInfoPanel(MatrixStack matrices) {
        List<Line> lines = this.info();
        int left = 10, right = Math.min(left + 260, this.cw - 20), top = 92;
        int bottom = Math.min(this.ch - BAR - 8, top + this.height(lines, right - left));
        this.infoRight = right;
        this.tooltipBox(matrices, left, top, right, bottom);
        this.scissor(left - 3, top - 3, right + 3, bottom + 3);
        this.renderLines(matrices, lines, left, top - this.infoScroll, right);
        this.noScissor();
    }

    private void drawButtons(MatrixStack matrices) {
        int y = this.ch - 24, x = 6;
        String viewName = this.view == null ? "Live" : this.view.id;
        x = this.button(matrices, x, y, 120, "View: " + viewName, this::nextView) + 4;
        x = this.button(matrices, x, y, 96, "Terrain: " + (terrainOn ? "ON" : "OFF"), () -> terrainOn = !terrainOn) + 4;
        x = this.button(matrices, x, y, 96, "Frustum: " + (frustumOn ? "ON" : "OFF"), () -> frustumOn = !frustumOn) + 4;
        x = this.button(matrices, x, y, 90, "Freeze: " + (frozen ? "ON" : "OFF"), () -> frozen = !frozen) + 4;
        x = this.button(matrices, x, y, 60, "Legend", () -> {
            legend = !legend;
            info = false;
        }) + 4;
        this.button(matrices, x, y, 50, "Info", () -> {
            info = !info;
            legend = false;
        });

        this.sliderW = Math.min(180, this.cw / 3);
        this.sliderX = this.cw - 6 - this.sliderW;
        String label = allY ? "Section Y: All" : "Section Y: " + this.sliceY + " (" + (this.sliceY << 4) + "-" + ((this.sliceY << 4) + 15) + ")";
        this.slider(matrices, this.sliderX, y, this.sliderW, allY ? 16 : this.sliceY, 16, label);
    }

    // AbstractButtonWidget.renderButton at our scale
    private int button(MatrixStack matrices, int x, int y, int w, String text, Runnable action) {
        boolean hover = this.inside(x, y, x + w, y + 20);
        this.widgets();
        int v = 46 + (hover ? 2 : 1) * 20;
        this.drawTexture(matrices, x, y, 0, v, w / 2, 20);
        this.drawTexture(matrices, x + w / 2, y, 200 - w / 2, v, w / 2, 20);
        drawCenteredString(matrices, this.textRenderer, text, x + w / 2, y + 6, WHITE);
        this.hits.add(new Hit(x, y, x + w, y + 20, action));
        return x + w;
    }

    // SliderWidget - disabled button texture as the track, 8px handle
    private void slider(MatrixStack matrices, int x, int y, int w, int value, int max, String text) {
        boolean hover = this.sliding || this.inside(x, y, x + w, y + 20);
        this.widgets();
        this.drawTexture(matrices, x, y, 0, 46, w / 2, 20);
        this.drawTexture(matrices, x + w / 2, y, 200 - w / 2, 46, w / 2, 20);
        int hx = x + (w - 8) * value / max, v = 46 + (hover ? 2 : 1) * 20;
        this.drawTexture(matrices, hx, y, 0, v, 4, 20);
        this.drawTexture(matrices, hx + 4, y, 196, v, 4, 20);
        drawCenteredString(matrices, this.textRenderer, text, x + w / 2, y + 6, WHITE);
    }

    private void widgets() {
        this.client.getTextureManager().bindTexture(AbstractButtonWidget.WIDGETS_LOCATION);
        RenderSystem.color4f(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private void drawLegend(MatrixStack matrices) {
        Status[] all = Status.values();
        int[] colors = {SPAWNER, BLOCK_ENTITY, ENTITY};
        String[] labels = {"Spawner", "Block entity", "Entities"};
        int w = 0;
        for (Status st : all) w = Math.max(w, this.textRenderer.getWidth(st.label));
        int h = (all.length + colors.length) * 10 + 4;
        int x = 10, y = this.ch - BAR - 8 - h;
        this.tooltipBox(matrices, x, y, x + w + 14, y + h);
        for (int i = 0; i < all.length; i++) {
            Status st = all[all.length - 1 - i];
            fill(matrices, x, y + i * 10, x + 8, y + i * 10 + 8, st.color);
            this.textRenderer.drawWithShadow(matrices, st.label, x + 13, y + i * 10, GRAY);
        }
        for (int i = 0; i < colors.length; i++) {
            int cy = y + (all.length + i) * 10 + 4;
            this.textRenderer.drawWithShadow(matrices, "■", x, cy, colors[i]);
            this.textRenderer.drawWithShadow(matrices, labels[i], x + 13, cy, GRAY);
        }
    }

    private int panelWidth() {
        return Math.min(PANEL, this.cw * 2 / 5);
    }

    private void drawPanel(MatrixStack matrices) {
        int pw = this.panelWidth();
        int left = this.cw - pw - 8, right = this.cw - 10, top = 10, bottom = this.ch - BAR - 8;
        this.tooltipBox(matrices, left, top, right, bottom);

        this.scissor(left - 3, top - 3, right + 3, bottom + 3);
        this.renderLines(matrices, this.details(), left, top - this.scroll, right);
        this.noScissor();
    }

    private int height(List<Line> lines, int width) {
        int h = 0;
        for (Line line : lines) {
            switch (line.kind) {
                case TITLE: h += 12; break;
                case HEAD: h += 16; break;
                case STRIP: h += 13; break;
                case MATRIX: h += 60; break;
                case EDGE: h += 10 * this.wrap(line.text, width - 10).size(); break;
                case ROW: h += 10; break;
                default: h += 10 * this.wrap(line.text, width).size();
            }
        }
        return h - 2;
    }

    private int renderLines(MatrixStack matrices, List<Line> lines, int left, int y, int right) {
        int width = right - left;
        for (Line line : lines) {
            switch (line.kind) {
                case TITLE:
                    this.textRenderer.drawWithShadow(matrices, line.text, left, y, line.color);
                    y += 12;
                    break;
                case HEAD:
                    y += 5;
                    this.textRenderer.drawWithShadow(matrices, line.text, left, y, YELLOW);
                    if (line.right != null) {
                        this.textRenderer.drawWithShadow(matrices, line.right, right - this.textRenderer.getWidth(line.right), y, GRAY);
                    }
                    y += 11;
                    break;
                // the 16 sections of the pinned column, click one to jump to it
                case STRIP: {
                    int step = Math.min(13, width / 16);
                    for (int sy = 0; sy < 16; sy++) {
                        Section sec = this.snap.get(this.pinX, sy, this.pinZ);
                        int cx = left + sy * step;
                        boolean sel = sy == this.sliceY && !allY;
                        fill(matrices, cx, y, cx + step - 1, y + 9, sel ? 0xFFFFFFFF : 0xFF000000);
                        fill(matrices, cx + 1, y + 1, cx + step - 2, y + 8, sec == null ? Status.UNLOADED.color : sec.status.color);
                        int row = sy;
                        this.hits.add(new Hit(cx, y, cx + step - 1, y + 9, () -> this.setY(row)));
                    }
                    y += 13;
                    break;
                }
                case ROW: {
                    int tx = left;
                    if (line.swatch != 0) {
                        this.textRenderer.drawWithShadow(matrices, "■", left, y, line.swatch);
                        tx += 9;
                    }
                    int rw = line.right == null ? 0 : this.textRenderer.getWidth(line.right);
                    String text = this.textRenderer.trimToWidth(line.text, width - rw - (tx - left) - 6);
                    this.textRenderer.drawWithShadow(matrices, text, tx, y, line.color);
                    // coords only show if they fit whole, a cut off one reads like a different coordinate
                    int nx = tx + this.textRenderer.getWidth(text) + 5;
                    if (line.note != null && nx + this.textRenderer.getWidth(line.note) <= right - rw - 6) {
                        this.textRenderer.drawWithShadow(matrices, line.note, nx, y, DARK_GRAY);
                    }
                    if (line.right != null) this.textRenderer.drawWithShadow(matrices, line.right, right - rw, y, line.rightColor);
                    y += 10;
                    break;
                }
                case EDGE: {
                    this.textRenderer.drawWithShadow(matrices, line.right, left, y, line.swatch);
                    for (String part : this.wrap(line.text, width - 10)) {
                        this.textRenderer.drawWithShadow(matrices, part, left + 10, y, line.color);
                        y += 10;
                    }
                    break;
                }
                case MATRIX:
                    y = this.drawSightLines(matrices, left, y);
                    break;
                default:
                    for (String part : this.wrap(line.text, width)) {
                        this.textRenderer.drawWithShadow(matrices, part, left, y, line.color);
                        y += 10;
                    }
            }
        }
        return y;
    }

    // one line per entry face -> the faces the search could leave through
    private int drawSightLines(MatrixStack matrices, int left, int y) {
        Section sec = this.snap.get(this.pinX, this.sliceY, this.pinZ);
        for (Direction from : Direction.values()) {
            StringBuilder exits = new StringBuilder();
            for (Direction to : Direction.values()) {
                if (to != from && sec.canSee(from, to)) exits.append(Snapshot.letter(to)).append(' ');
            }
            boolean entry = sec.visited() && sec.flow == from;
            int x = left;
            this.textRenderer.drawWithShadow(matrices, Snapshot.letter(from), x, y, entry ? GREEN : WHITE);
            x += 9;
            this.textRenderer.drawWithShadow(matrices, "→", x, y, DARK_GRAY);
            x += 11;
            this.textRenderer.drawWithShadow(matrices, exits.length() == 0 ? "nothing" : exits.toString().trim(), x, y, exits.length() == 0 ? DARK_GRAY : entry ? GREEN : GRAY);
            y += 10;
        }
        return y;
    }

    // right click panel
    private List<Line> details() {
        List<Line> out = new ArrayList<>();
        Section sec = this.snap.get(this.pinX, this.sliceY, this.pinZ);
        out.add(new Line(Line.Kind.TITLE, "Section " + this.pinX + ", " + this.sliceY + ", " + this.pinZ, WHITE));
        if (sec == null) {
            out.add(new Line(Line.Kind.TEXT, Status.UNLOADED.label, lighten(Status.UNLOADED.color)));
            return out;
        }
        out.add(new Line(Line.Kind.ROW, sec.status.label, lighten(sec.status.color)).right(String.format("%.0f blocks away", sec.distance), GRAY));
        out.add(new Line(Line.Kind.TEXT, "Blocks " + (this.pinX << 4) + " " + (this.sliceY << 4) + " " + (this.pinZ << 4) + " to "
                + ((this.pinX << 4) + 15) + " " + ((this.sliceY << 4) + 15) + " " + ((this.pinZ << 4) + 15), DARK_GRAY));
        out.add(new Line(Line.Kind.STRIP, "", 0));
        out.add(row("Mesh", mesh(sec)));
        if (sec.rebuild) out.add(row("Rebuild", "queued"));
        if (sec.fogged) out.add(row("Fog", "past the cutoff, skipped"));

        out.add(new Line(Line.Kind.HEAD, "Culling", YELLOW));
        Snapshot.Cull cull = this.snap.cull(sec);
        String type = !sec.visited() && !sec.inFrustum && cull != Snapshot.Cull.FRUSTUM ? "Frustum, then " + cull.label.toLowerCase(Locale.ROOT) : cull.label;
        out.add(new Line(Line.Kind.ROW, "Type", GRAY).right(type, sec.visited() ? GREEN : GOLD));
        Route route = sec.visited() ? null : this.snap.route(sec);
        if (route != null) {
            // stronghold-culling-explainer's name for it
            out.add(new Line(Line.Kind.ROW, "Group", GRAY).right(route.group.label, GOLD));
        }
        if (sec.visited()) {
            out.add(row("Order", "#" + sec.order + " of " + this.snap.visible));
            if (sec.flow == null) {
                out.add(row("Flow", "start of the search"));
            } else {
                Section parent = this.snap.get(sec.x + sec.flow.getOffsetX(), sec.y + sec.flow.getOffsetY(), sec.z + sec.flow.getOffsetZ());
                out.add(row("Flow", "in through " + sec.flow.getName() + (parent != null ? " from #" + parent.order : "")));
            }
            // pastry's name for the culling state - directions the search can't go from here anymore
            out.add(row("Blocked", Snapshot.letters(sec.bans)));
        } else if (sec.inRange) {
            // only neighbours the search reached could've let it in
            int unreached = 0;
            for (Edge e : this.snap.explain(sec)) {
                if (e.from < 0) {
                    unreached++;
                    continue;
                }
                Line edge = new Line(Line.Kind.EDGE, "#" + e.from + " " + e.text, WHITE).right(Snapshot.letter(e.dir), 0);
                edge.swatch = e.kind == Edge.Kind.OUTSIDE_FOV ? GRAY : e.kind == Edge.Kind.OPEN ? GOLD : 0xFF5555;
                out.add(edge);
            }
            if (unreached > 0) {
                out.add(new Line(Line.Kind.TEXT, unreached + " other neighbour" + (unreached == 1 ? " wasn't" : "s weren't") + " reached", DARK_GRAY));
            }
            int stale = this.snap.staleNeighbours(sec);
            if (stale > 0) {
                out.add(new Line(Line.Kind.TEXT, stale + " neighbour" + (stale == 1 ? " has an" : "s have") + " out-of-date mesh"
                        + (stale == 1 ? "" : "es") + "; Sodium culls with the old sight lines until it can see " + (stale == 1 ? "it" : "them"), GOLD));
            }
        }

        out.add(new Line(Line.Kind.HEAD, "Sight lines", YELLOW).right("in → out", GRAY));
        out.add(new Line(Line.Kind.MATRIX, "", 0));

        if (!sec.blockEntities.isEmpty()) {
            out.add(new Line(Line.Kind.HEAD, "Block entities", YELLOW).right(sec.drawnBlockEntities() + " of " + sec.blockEntities.size() + " drawn", GRAY));
            for (BlockEntityInfo be : sec.blockEntities) {
                String reason = this.snap.reason(be, sec);
                Line l = new Line(Line.Kind.ROW, be.mob != null ? be.mob + " spawner" : be.type.replace('_', ' '), be.drawn ? WHITE : GRAY)
                        .right(reason, be.drawn ? GREEN : be.queued ? GOLD : DARK_GRAY);
                l.note = be.pos.getX() + " " + be.pos.getY() + " " + be.pos.getZ();
                l.swatch = be.mob != null ? SPAWNER : BLOCK_ENTITY;
                out.add(l);
            }
        }

        int others = 0, drawn = 0;
        Map<String, Integer> groups = new TreeMap<>();
        for (EntityInfo e : sec.entities) {
            if (e.self) continue;
            others++;
            if (e.drawn) drawn++;
            // grouped by type + reason
            groups.merge(e.type + "\u0000" + this.snap.reason(e), 1, Integer::sum);
        }
        if (others > 0) {
            out.add(new Line(Line.Kind.HEAD, "Entities", YELLOW).right(drawn + " of " + others + " drawn", GRAY));
            for (Map.Entry<String, Integer> e : groups.entrySet()) {
                String[] key = e.getKey().split("\u0000");
                boolean isDrawn = key[1].equals("drawn");
                Line l = new Line(Line.Kind.ROW, key[0] + " ×" + e.getValue(), isDrawn ? WHITE : GRAY).right(key[1], isDrawn ? GREEN : DARK_GRAY);
                l.swatch = ENTITY;
                out.add(l);
            }
        }
        return out;
    }

    private void drawTooltip(MatrixStack matrices) {
        int x = this.chunkX(this.mx), z = this.chunkZ(this.my);
        List<Section> col = this.column(x, z);
        Status status = this.status(x, z);
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add(status.label);
        colors.add(lighten(status.color));
        if (!allY && !col.isEmpty() && !col.get(0).visited()) {
            lines.add("Culling: " + this.snap.cull(col.get(0)).label);
            colors.add(GOLD);
        }

        if (allY) {
            StringBuilder ys = new StringBuilder();
            for (Section sec : col) {
                if (sec.visited()) ys.append(ys.length() == 0 ? "" : " ").append(sec.y);
            }
            lines.add(x + ", " + z + (ys.length() == 0 ? "" : " · reached at y " + ys));
        } else {
            Section sec = col.isEmpty() ? null : col.get(0);
            String where = x + ", " + this.sliceY + ", " + z;
            if (sec != null) where += " · " + (sec.visited() ? "#" + sec.order + " · " : "") + mesh(sec);
            lines.add(where);
        }
        colors.add(GRAY);

        int be = 0, beDrawn = 0, ent = 0, entDrawn = 0;
        boolean spawner = false;
        for (Section sec : col) {
            for (BlockEntityInfo info : sec.blockEntities) {
                be++;
                if (info.drawn) beDrawn++;
                spawner |= info.mob != null;
            }
            for (EntityInfo e : sec.entities) {
                if (e.self) continue;
                ent++;
                if (e.drawn) entDrawn++;
            }
        }
        if (be > 0) {
            lines.add(beDrawn + " of " + be + " block entit" + (be == 1 ? "y" : "ies") + " drawn");
            colors.add(spawner ? SPAWNER : BLOCK_ENTITY);
        }
        if (ent > 0) {
            lines.add(entDrawn + " of " + ent + " entit" + (ent == 1 ? "y" : "ies") + " drawn");
            colors.add(ENTITY);
        }

        int w = 0;
        for (String l : lines) w = Math.max(w, this.textRenderer.getWidth(l));
        int h = lines.size() * 10 - 2;
        int tx = (int) this.mx + 12, ty = (int) this.my - 12;
        int limit = this.pinned ? this.cw - this.panelWidth() - 14 : this.cw;
        if (tx + w + 6 > limit) tx = (int) this.mx - w - 16;
        if (ty + h + 6 > this.ch) ty = this.ch - h - 6;
        if (ty < 6) ty = 6;

        this.tooltipBox(matrices, tx, ty, tx + w, ty + h);
        for (int i = 0; i < lines.size(); i++) {
            this.textRenderer.drawWithShadow(matrices, lines.get(i), tx, ty + i * 10, colors.get(i));
        }
    }

    // Screen.renderTooltip's background + border around a rect
    private void tooltipBox(MatrixStack matrices, int x0, int y0, int x1, int y1) {
        this.fillGradient(matrices, x0 - 3, y0 - 4, x1 + 3, y0 - 3, TIP_BG, TIP_BG);
        this.fillGradient(matrices, x0 - 3, y1 + 3, x1 + 3, y1 + 4, TIP_BG, TIP_BG);
        this.fillGradient(matrices, x0 - 3, y0 - 3, x1 + 3, y1 + 3, TIP_BG, TIP_BG);
        this.fillGradient(matrices, x0 - 4, y0 - 3, x0 - 3, y1 + 3, TIP_BG, TIP_BG);
        this.fillGradient(matrices, x1 + 3, y0 - 3, x1 + 4, y1 + 3, TIP_BG, TIP_BG);
        this.fillGradient(matrices, x0 - 3, y0 - 2, x0 - 2, y1 + 2, TIP_BORDER, TIP_BORDER_END);
        this.fillGradient(matrices, x1 + 2, y0 - 2, x1 + 3, y1 + 2, TIP_BORDER, TIP_BORDER_END);
        this.fillGradient(matrices, x0 - 3, y0 - 3, x1 + 3, y0 - 2, TIP_BORDER, TIP_BORDER);
        this.fillGradient(matrices, x0 - 3, y1 + 2, x1 + 3, y1 + 3, TIP_BORDER_END, TIP_BORDER_END);
    }

    private Status status(int x, int z) {
        if (!allY) {
            Section sec = this.snap.get(x, this.sliceY, z);
            return sec == null ? Status.UNLOADED : sec.status;
        }
        Status best = Status.UNLOADED;
        for (int y = 0; y < 16; y++) {
            Section sec = this.snap.get(x, y, z);
            if (sec != null && sec.status.ordinal() > best.ordinal()) best = sec.status;
        }
        return best;
    }

    private List<Section> column(int x, int z) {
        List<Section> out = new ArrayList<>(allY ? 16 : 1);
        for (int y = allY ? 0 : this.sliceY; y <= (allY ? 15 : this.sliceY); y++) {
            Section sec = this.snap.get(x, y, z);
            if (sec != null) out.add(sec);
        }
        return out;
    }

    private void select(Toolscreen.Mode mode) {
        this.view = mode;
        this.sim = null;
    }

    private void nextView() {
        List<Toolscreen.Mode> modes = this.toolscreen == null ? new ArrayList<>() : this.toolscreen.modes;
        int i = this.view == null ? -1 : modes.indexOf(this.view);
        this.select(i + 1 < modes.size() ? modes.get(i + 1) : null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // esc closes the panel first
        if (key == GLFW.GLFW_KEY_ESCAPE && this.pinned) {
            this.pinned = false;
            return true;
        }
        if (Biscuits.OPEN.matchesKey(key, scan)) {
            this.onClose();
        } else if (Biscuits.UP.matchesKey(key, scan)) {
            this.moveY(1);
        } else if (Biscuits.DOWN.matchesKey(key, scan)) {
            this.moveY(-1);
        } else if (Biscuits.VIEW.matchesKey(key, scan)) {
            this.nextView();
        } else if (Biscuits.CAMERA.matchesKey(key, scan)) {
            this.followY = true;
            this.centered = false;
        } else if (Biscuits.ALL_Y.matchesKey(key, scan)) {
            allY = !allY;
        } else if (Biscuits.TERRAIN.matchesKey(key, scan)) {
            terrainOn = !terrainOn;
        } else if (Biscuits.FRUSTUM.matchesKey(key, scan)) {
            frustumOn = !frustumOn;
        } else if (Biscuits.FREEZE.matchesKey(key, scan)) {
            frozen = !frozen;
        } else if (Biscuits.LEGEND.matchesKey(key, scan)) {
            legend = !legend;
        } else {
            return super.keyPressed(key, scan, mods);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX / this.ui, y = mouseY / this.ui;
        if (this.snap == null) return false;
        if (button == 0) {
            for (Hit hit : this.hits) {
                if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) {
                    this.playClick();
                    hit.action.run();
                    return true;
                }
            }
            if (x >= this.sliderX && x < this.sliderX + this.sliderW && y >= this.ch - 24 && y < this.ch - 4) {
                this.sliding = true;
                this.slideTo(x);
                return true;
            }
        }
        if (!this.overMap(x, y)) return true;

        if (button == 1) {
            int cx = this.chunkX(x), cz = this.chunkZ(y);
            if (this.pinned && cx == this.pinX && cz == this.pinZ) {
                this.pinned = false;
            } else {
                this.pinned = true;
                this.pinX = cx;
                this.pinZ = cz;
                this.scroll = 0;
            }
            return true;
        }
        if (button == 0) {
            this.dragging = true;
            return true;
        }
        return false;
    }

    private void playClick() {
        this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // 0-15 are section ys, the last notch is All
    private void slideTo(double x) {
        int v = MathHelper.clamp((int) Math.round((x - this.sliderX - 4) / (this.sliderW - 8) * 16), 0, 16);
        if (v == 16) {
            allY = true;
            this.followY = false;
        } else {
            this.setY(v);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.sliding) this.playClick();
            this.dragging = false;
            this.sliding = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button != 0) return false;
        if (this.sliding) {
            this.slideTo(mouseX / this.ui);
            return true;
        }
        if (this.dragging) {
            this.viewX -= dx / this.ui / this.cell;
            this.viewZ -= dy / this.ui / this.cell;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        double x = mouseX / this.ui, y = mouseY / this.ui;
        // ctrl/shift + scroll or scrolling on the slider changes y, otherwise zoom
        boolean onSlider = x >= this.sliderX && x < this.sliderX + this.sliderW && y >= this.ch - BAR;
        if (hasControlDown() || hasShiftDown() || onSlider) {
            this.moveY(amount > 0 ? 1 : -1);
            return true;
        }
        if (this.pinned && x >= this.cw - this.panelWidth() - 14) {
            this.scroll = Math.max(0, this.scroll - (int) (amount * 20));
            return true;
        }
        if (info && x < this.infoRight + 4 && y >= 88) {
            this.infoScroll = Math.max(0, this.infoScroll - (int) (amount * 20));
            return true;
        }
        // zoom around the cursor
        double wx = this.viewX + (x - this.midX()) / this.cell;
        double wz = this.viewZ + (y - this.midY()) / this.cell;
        this.cell = MathHelper.clamp(this.cell * (amount > 0 ? 1.25 : 0.8), 3, 64);
        this.viewX = wx - (x - this.midX()) / this.cell;
        this.viewZ = wz - (y - this.midY()) / this.cell;
        return true;
    }

    private void moveY(int dy) {
        this.setY(MathHelper.clamp(this.sliceY + dy, 0, 15));
    }

    private void setY(int y) {
        this.followY = false;
        allY = false;
        this.sliceY = y;
    }

    private boolean inside(int x0, int y0, int x1, int y1) {
        return this.mx >= x0 && this.mx < x1 && this.my >= y0 && this.my < y1;
    }

    // everything except the button row and the panel
    private boolean overMap(double x, double y) {
        return this.snap != null && y < this.ch - BAR && !(this.pinned && x >= this.cw - this.panelWidth() - 14);
    }

    private double midX() {
        return this.cw / 2.0;
    }

    private double midY() {
        return (this.ch - BAR) / 2.0;
    }

    private float sx(double chunkX) {
        return (float) (this.midX() + (chunkX - this.viewX) * this.cell);
    }

    private float sz(double chunkZ) {
        return (float) (this.midY() + (chunkZ - this.viewZ) * this.cell);
    }

    private int chunkX(double x) {
        return MathHelper.floor(this.viewX + (x - this.midX()) / this.cell);
    }

    private int chunkZ(double y) {
        return MathHelper.floor(this.viewZ + (y - this.midY()) / this.cell);
    }

    private void scissor(int x0, int y0, int x1, int y1) {
        double s = this.ui * this.client.getWindow().getScaleFactor();
        int fh = this.client.getWindow().getFramebufferHeight();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x0 * s), (int) (fh - y1 * s), (int) ((x1 - x0) * s), (int) ((y1 - y0) * s));
    }

    private void noScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        String indent = "  ";
        while (this.textRenderer.getWidth(text) > width) {
            String head = this.textRenderer.trimToWidth(text, width);
            int cut = head.lastIndexOf(' ');
            if (cut <= indent.length()) cut = head.length();
            out.add(text.substring(0, cut));
            text = indent + text.substring(cut).trim();
        }
        out.add(text);
        return out;
    }

    private static Line row(String label, String value) {
        return new Line(Line.Kind.ROW, label, GRAY).right(value, WHITE);
    }

    private static String mesh(Section sec) {
        // sodium only meshes a section once all 8 neighbouring columns are loaded
        if (!sec.built) return sec.canBuild ? "not built yet" : "not built, neighbour chunks missing";
        return sec.geometry ? String.format("%.1f KB", sec.meshBytes / 1024f) : "no geometry";
    }

    private static BufferBuilder begin() {
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder b = Tessellator.getInstance().getBuffer();
        b.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        return b;
    }

    private static void end() {
        if (Tessellator.getInstance().getBuffer().isBuilding()) {
            Tessellator.getInstance().draw();
        }
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        float a = (argb >>> 24) / 255f, r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, bl = (argb & 255) / 255f;
        b.vertex(m, x0, y1, 0).color(r, g, bl, a).next();
        b.vertex(m, x1, y1, 0).color(r, g, bl, a).next();
        b.vertex(m, x1, y0, 0).color(r, g, bl, a).next();
        b.vertex(m, x0, y0, 0).color(r, g, bl, a).next();
    }

    private static void outline(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        quad(b, m, x0, y0, x1, y0 + 1, argb);
        quad(b, m, x0, y1 - 1, x1, y1, argb);
        quad(b, m, x0, y0 + 1, x0 + 1, y1 - 1, argb);
        quad(b, m, x1 - 1, y0 + 1, x1, y1 - 1, argb);
    }

    private static int lighten(int argb) {
        int r = Math.min(255, (argb >> 16 & 255) + 70), g = Math.min(255, (argb >> 8 & 255) + 70), b = Math.min(255, (argb & 255) + 70);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int total(Map<String, Integer> counts) {
        int n = 0;
        for (int c : counts.values()) n += c;
        return n;
    }

    // " (chest 1, mob_spawner (pig) 1)", like pastry's breakdown
    private static String list(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" (");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 2) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(e.getValue());
        }
        return sb.append(')').toString();
    }

    private static final class Hit {
        final int x0, y0, x1, y1;
        final Runnable action;

        Hit(int x0, int y0, int x1, int y1, Runnable action) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.action = action;
        }
    }

    private static final class Line {
        enum Kind { TITLE, HEAD, STRIP, ROW, EDGE, MATRIX, TEXT }

        final Kind kind;
        final String text;
        final int color;
        String right, note;
        int rightColor;
        int swatch;

        Line(Kind kind, String text, int color) {
            this.kind = kind;
            this.text = text;
            this.color = color;
        }

        Line right(String text, int color) {
            this.right = text;
            this.rightColor = color;
            return this;
        }
    }
}
