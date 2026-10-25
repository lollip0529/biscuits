package io.github.lollip0529.biscuits;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.List;

public final class Biscuits {
    public static final String CATEGORY = "key.categories.biscuits";
    // all unbound, set them in controls
    public static final KeyBinding OPEN = new KeyBinding("key.biscuits.open", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding ESP = new KeyBinding("key.biscuits.esp", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);

    // map only. KeyBindingMixin keeps these out of the game's key lookup so they can share keys with normal controls
    public static final KeyBinding VIEW = new KeyBinding("key.biscuits.view", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding ALL_Y = new KeyBinding("key.biscuits.all_y", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding UP = new KeyBinding("key.biscuits.up", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding DOWN = new KeyBinding("key.biscuits.down", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding CAMERA = new KeyBinding("key.biscuits.camera", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding TERRAIN = new KeyBinding("key.biscuits.terrain", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding FRUSTUM = new KeyBinding("key.biscuits.frustum", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding FREEZE = new KeyBinding("key.biscuits.freeze", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final KeyBinding LEGEND = new KeyBinding("key.biscuits.legend", InputUtil.UNKNOWN_KEY.getCode(), CATEGORY);
    public static final List<KeyBinding> ALL = Arrays.asList(OPEN, ESP, VIEW, ALL_Y, UP, DOWN, CAMERA, TERRAIN, FRUSTUM, FREEZE, LEGEND);
    public static final List<KeyBinding> MAP_ONLY = ALL.subList(2, ALL.size());

    // what actually got rendered last frame. drawing* fills up during the frame, then gets swapped into drawn*
    private static IntSet drawingEntities = new IntOpenHashSet();
    private static IntSet drawnEntities = new IntOpenHashSet();
    private static LongSet drawingBlockEntities = new LongOpenHashSet();
    private static LongSet drawnBlockEntities = new LongOpenHashSet();
    private static boolean inWorld;
    private static BlockPos drawingOutline, drawnOutline;

    private static Matrix4f frameModel, frameProjection;
    private static Vec3d frameCamera;

    // matrices + window size from the frame sodium last ran its graph search in. a resize (toolscreen mode switch)
    // triggers a new search through WorldRenderer.onResized
    static Matrix4f searchModel, searchProjection;
    static Vec3d searchCamera;
    static int searchWidth, searchHeight;

    private Biscuits() {
    }

    public static void beginWorldFrame(Matrix4f model, Matrix4f projection, Vec3d camera) {
        inWorld = true;
        drawingEntities.clear();
        drawingBlockEntities.clear();
        drawingOutline = null;
        frameModel = model;
        frameProjection = projection;
        frameCamera = camera;
    }

    public static void endWorldFrame() {
        inWorld = false;

        IntSet e = drawnEntities;
        drawnEntities = drawingEntities;
        drawingEntities = e;

        LongSet be = drawnBlockEntities;
        drawnBlockEntities = drawingBlockEntities;
        drawingBlockEntities = be;

        drawnOutline = drawingOutline;
    }

    // block outline, pastry tracks this too
    public static void outlineDrawn(BlockPos pos) {
        if (inWorld) {
            drawingOutline = pos.toImmutable();
        }
    }

    public static BlockPos drawnOutline() {
        return drawnOutline;
    }

    public static void searchRan() {
        if (frameModel == null) return;
        searchModel = frameModel.copy();
        searchProjection = frameProjection.copy();
        searchCamera = frameCamera;
        Window window = MinecraftClient.getInstance().getWindow();
        searchWidth = window.getFramebufferWidth();
        searchHeight = window.getFramebufferHeight();
    }

    public static void entityDrawn(int id) {
        if (inWorld) {
            drawingEntities.add(id);
        }
    }

    public static void blockEntityDrawn(long pos) {
        if (inWorld) {
            drawingBlockEntities.add(pos);
        }
    }

    public static IntSet drawnEntities() {
        return drawnEntities;
    }

    public static LongSet drawnBlockEntities() {
        return drawnBlockEntities;
    }
}
