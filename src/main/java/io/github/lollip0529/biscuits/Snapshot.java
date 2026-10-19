package io.github.lollip0529.biscuits;

import io.github.lollip0529.biscuits.mixin.Matrix4fAccessor;
import io.github.lollip0529.biscuits.mixin.sodium.ChunkGraphCullerAccessor;
import io.github.lollip0529.biscuits.mixin.sodium.ChunkGraphNodeAccessor;
import io.github.lollip0529.biscuits.mixin.sodium.ChunkRenderManagerAccessor;
import io.github.lollip0529.biscuits.mixin.sodium.SodiumWorldRendererAccessor;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderContainer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphIterationQueue;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphNode;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.util.math.FrustumExtended;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// copy of sodium's last graph search + what the last frame actually drew. simulate() replays it for a different
// window size
public class Snapshot {
    // worst to best, all-y mode shows the best one in each column
    public enum Status {
        UNLOADED("Not loaded", 0xFF6B1A1A),
        OUT_OF_RANGE("Outside render distance", 0xFF3D2A55),
        OUTSIDE_FOV("Culled, outside FOV", 0xFF2A2A2A),
        CULLED("Culled, in FOV", 0xFFFFAA00),
        OPEN("Not culled, outside FOV", 0xFF1F7A1F),
        VISIBLE("Not culled, in FOV", 0xFF55E055);

        public final String label;
        public final int color;

        Status(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    // why a section isn't reached, same as the practice map's classifier - rerun the search with one rule relaxed at
    // a time, first one that gets in wins
    public enum Cull {
        NONE("Not culled"),
        FRUSTUM("Frustum"),
        ANGLE("Angle"),
        HORIZONTAL("Horizontal backtracking"),
        VERTICAL("Vertical backtracking"),
        BACKTRACKING("Backtracking"),
        AIR_PATH("Air path"),
        BLOCKED("Blocked"),
        RANGE("Render distance");

        public final String label;

        Cull(String label) {
            this.label = label;
        }
    }

    // culling state bits are direction ordinals: down up north south west east
    private static final int ALL_BANS = 0x3F, VERTICAL_BANS = 0x03, HORIZONTAL_BANS = 0x3C;

    public static class Section {
        public final int x, y, z;
        public long through;
        public boolean inRange;
        public boolean fogged;
        public boolean built;
        public boolean canBuild;
        public boolean geometry;
        public boolean rebuild;
        public int meshBytes;
        public double distance;
        public List<BlockEntityInfo> blockEntities = new ArrayList<>();
        public List<EntityInfo> entities = new ArrayList<>();

        // search results
        public Status status;
        // reached with the frustum test off
        public boolean open;
        public int order = -1;
        public Direction flow;
        public byte bans;
        public boolean inFrustum;

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Section copy() {
            Section s = new Section(this.x, this.y, this.z);
            s.through = this.through;
            s.inRange = this.inRange;
            s.fogged = this.fogged;
            s.built = this.built;
            s.canBuild = this.canBuild;
            s.open = this.open;
            s.geometry = this.geometry;
            s.rebuild = this.rebuild;
            s.meshBytes = this.meshBytes;
            s.distance = this.distance;
            for (BlockEntityInfo be : this.blockEntities) s.blockEntities.add(be.copy());
            for (EntityInfo e : this.entities) s.entities.add(e.copy());
            return s;
        }

        public boolean visited() {
            return this.order >= 0;
        }

        public boolean bans(Direction dir) {
            return (this.bans & 1 << dir.ordinal()) != 0;
        }

        public boolean canSee(Direction from, Direction to) {
            return (this.through & 1L << ((from.ordinal() << 3) + to.ordinal())) != 0L;
        }

        public int drawnBlockEntities() {
            int n = 0;
            for (BlockEntityInfo be : this.blockEntities) {
                if (be.drawn) n++;
            }
            return n;
        }

        public int drawnEntities() {
            int n = 0;
            for (EntityInfo e : this.entities) {
                if (e.drawn) n++;
            }
            return n;
        }
    }

    public static class BlockEntityInfo {
        public final String type;
        public final String mob;
        public final BlockPos pos;
        public final boolean renderer;
        // in the mesh's culled list, or sodium's always drawn list (beacons etc)
        public final boolean inMesh;
        public final boolean global;
        public final double distance;
        public final double range;
        public boolean queued;
        public boolean drawn;

        BlockEntityInfo(String type, String mob, BlockPos pos, boolean renderer, boolean inMesh, boolean global, double distance, double range) {
            this.type = type;
            this.mob = mob;
            this.pos = pos;
            this.renderer = renderer;
            this.inMesh = inMesh;
            this.global = global;
            this.distance = distance;
            this.range = range;
        }

        BlockEntityInfo copy() {
            return new BlockEntityInfo(this.type, this.mob, this.pos, this.renderer, this.inMesh, this.global, this.distance, this.range);
        }

        public String name() {
            return this.mob == null ? this.type : this.type + " (" + this.mob + ")";
        }
    }

    public static class EntityInfo {
        public final String type;
        public final Vec3d pos;
        public final boolean self;
        // EntityRenderer.shouldRender inputs: distance check, frustum opt out, the box it tests
        public final boolean inRange;
        public final boolean ignoresFrustum;
        public final Box box;
        public final Box cullBox;
        public boolean inView;
        public boolean drawn;

        EntityInfo(String type, Vec3d pos, boolean self, boolean inRange, boolean ignoresFrustum, Box box, Box cullBox) {
            this.type = type;
            this.pos = pos;
            this.self = self;
            this.inRange = inRange;
            this.ignoresFrustum = ignoresFrustum;
            this.box = box;
            this.cullBox = cullBox;
        }

        EntityInfo copy() {
            EntityInfo e = new EntityInfo(this.type, this.pos, this.self, this.inRange, this.ignoresFrustum, this.box, this.cullBox);
            e.inView = this.inView;
            return e;
        }
    }

    public static class Edge {
        public enum Kind { NO_SECTION, NOT_VISIBLE, BANNED, NO_PATH, OUTSIDE_FOV, OPEN }

        public final Direction dir;
        public final Kind kind;
        public final int from;
        public final String text;

        Edge(Direction dir, Kind kind, int from, String text) {
            this.dir = dir;
            this.kind = kind;
            this.from = from;
            this.text = text;
        }
    }

    public final Long2ObjectMap<Section> sections = new Long2ObjectOpenHashMap<>();
    public final Map<String, Integer> drawnBlockEntities = new TreeMap<>();
    public final Map<String, Integer> drawnEntities = new TreeMap<>();
    public String label = "Live";
    public int centerX, centerY, centerZ;
    public int renderDistance;
    public boolean occlusion;
    public boolean fogCulling;
    public boolean entityCulling;
    public Vec3d camera;
    public float yaw, pitch;
    public int visible, drawn;
    public long time;
    private int banMask = ALL_BANS;
    private Long2ObjectMap<Cull> culls;
    private final Long2ObjectMap<Route> routes = new Long2ObjectOpenHashMap<>();

    public Matrix4f model, projection;
    public Vec3d searchCamera;
    public int searchWidth, searchHeight;

    public Section get(int x, int y, int z) {
        return this.sections.get(ChunkSectionPos.asLong(x, y, z));
    }

    public static Snapshot capture(MinecraftClient mc) {
        if (mc.world == null || Biscuits.searchModel == null) return null;

        ChunkRenderManager<?> manager = ((SodiumWorldRendererAccessor) SodiumWorldRenderer.getInstance()).biscuits$manager();
        if (manager == null) return null;

        ChunkRenderManagerAccessor m = (ChunkRenderManagerAccessor) manager;
        ChunkGraphCullerAccessor culler = (ChunkGraphCullerAccessor) m.biscuits$culler();
        FrustumExtended frustum = culler.biscuits$frustum();
        if (frustum == null) return null;

        Snapshot s = new Snapshot();
        s.time = System.currentTimeMillis();
        s.centerX = culler.biscuits$centerX();
        s.centerY = culler.biscuits$centerY();
        s.centerZ = culler.biscuits$centerZ();
        s.renderDistance = culler.biscuits$renderDistance();
        s.occlusion = culler.biscuits$occlusion();
        s.fogCulling = m.biscuits$fogCulling();
        s.entityCulling = SodiumClientMod.options().advanced.useEntityCulling;
        s.model = Biscuits.searchModel;
        s.projection = Biscuits.searchProjection;
        s.searchCamera = Biscuits.searchCamera;
        s.searchWidth = Biscuits.searchWidth;
        s.searchHeight = Biscuits.searchHeight;

        Camera camera = mc.gameRenderer.getCamera();
        s.camera = camera.getPos();
        s.yaw = camera.getYaw();
        s.pitch = camera.getPitch();

        LongSet columns = new LongOpenHashSet();
        for (ChunkGraphNode node : culler.biscuits$nodes().values()) {
            Section sec = new Section(node.getChunkX(), node.getChunkY(), node.getChunkZ());
            sec.through = ((ChunkGraphNodeAccessor) node).biscuits$visibility();
            sec.inRange = Math.abs(sec.x - s.centerX) <= s.renderDistance && Math.abs(sec.z - s.centerZ) <= s.renderDistance;
            sec.inFrustum = !node.isCulledByFrustum(frustum);
            sec.distance = Math.sqrt(node.getSquaredDistance(s.camera.x, s.camera.y, s.camera.z));
            s.sections.put(ChunkSectionPos.asLong(sec.x, sec.y, sec.z), sec);
            columns.add(ChunkSectionPos.asLong(sec.x, 0, sec.z));
        }

        ChunkGraphIterationQueue queue = culler.biscuits$queue();
        for (int i = 0; i < queue.size(); i++) {
            ChunkGraphNode node = queue.getNode(i);
            Section sec = s.get(node.getChunkX(), node.getChunkY(), node.getChunkZ());
            if (sec != null && sec.order < 0) {
                sec.order = i;
                sec.flow = queue.getDirection(i);
                // nodes keep the culling state from whatever search last reached them, so only trust it here
                sec.bans = node.getCullingState();
            }
        }

        // meshes + fog
        double fogCutoff = m.biscuits$fogCutoff();
        Vector3f look = m.biscuits$cameraVector();
        float cx = m.biscuits$cameraX(), cy = m.biscuits$cameraY(), cz = m.biscuits$cameraZ();
        Long2ObjectMap<ChunkRenderData> meshes = new Long2ObjectOpenHashMap<>();

        for (Section sec : s.sections.values()) {
            ChunkRenderContainer<?> render = manager.getRender(sec.x, sec.y, sec.z);
            if (render == null) continue;
            ChunkRenderData data = render.getData();
            meshes.put(ChunkSectionPos.asLong(sec.x, sec.y, sec.z), data);
            sec.built = data != ChunkRenderData.ABSENT;
            sec.canBuild = render.canRebuild();
            sec.geometry = !render.isEmpty();
            sec.rebuild = render.needsRebuild();
            sec.meshBytes = data.getMeshSize();
            if (s.fogCulling) {
                double d = m.biscuits$planarFog() && look != null
                        ? render.getSquaredDepth(cx, cy, cz, look)
                        : render.getSquaredDistance(cx, cy, cz);
                sec.fogged = d >= fogCutoff;
            }
        }

        IntSet drawnEntities = Biscuits.drawnEntities();
        LongSet drawnBlockEntities = Biscuits.drawnBlockEntities();
        LongSet queued = new LongOpenHashSet();
        for (BlockEntity be : manager.getVisibleBlockEntities()) {
            queued.add(be.getPos().asLong());
        }

        // block entities and entities, per column
        for (long col : columns) {
            int x = ChunkSectionPos.getX(col), z = ChunkSectionPos.getZ(col);
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z, false);
            if (chunk == null) continue;

            for (BlockEntity be : chunk.getBlockEntities().values()) {
                int y = be.getPos().getY() >> 4;
                Section sec = s.get(x, y, z);
                if (sec == null) continue;

                String mob = null;
                if (be instanceof MobSpawnerBlockEntity) {
                    Entity shown = ((MobSpawnerBlockEntity) be).getLogic().getRenderedEntity();
                    mob = shown == null ? "?" : EntityType.getId(shown.getType()).getPath();
                }
                ChunkRenderData data = meshes.get(ChunkSectionPos.asLong(x, y, z));
                long pos = be.getPos().asLong();
                // the dispatcher skips anything not strictly closer than this. despite the name it's a radius (64,
                // 256 for beacons)
                double range = be.getSquaredRenderDistance();
                double distance = Math.sqrt(Vec3d.ofCenter(be.getPos()).squaredDistanceTo(s.camera));
                BlockEntityInfo info = new BlockEntityInfo(Registry.BLOCK_ENTITY_TYPE.getId(be.getType()).getPath(), mob, be.getPos(),
                        BlockEntityRenderDispatcher.INSTANCE.get(be) != null,
                        data != null && contains(data.getBlockEntities(), be), data != null && contains(data.getGlobalBlockEntities(), be),
                        distance, range);
                info.queued = queued.contains(pos);
                info.drawn = drawnBlockEntities.contains(pos);
                sec.blockEntities.add(info);
            }

            TypeFilterableList<Entity>[] lists = chunk.getEntitySectionArray();
            for (int y = 0; y < lists.length; y++) {
                Section sec = s.get(x, y, z);
                if (sec == null) continue;

                for (Entity e : lists[y]) {
                    // EntityRenderer.shouldRender pads the box, and uses a 4 block one if it's degenerate
                    Box box = e.getVisibilityBoundingBox().expand(0.5);
                    if (box.isValid() || box.getAverageSideLength() == 0.0) {
                        box = new Box(e.getX() - 2, e.getY() - 2, e.getZ() - 2, e.getX() + 2, e.getY() + 2, e.getZ() + 2);
                    }
                    EntityInfo info = new EntityInfo(EntityType.getId(e.getType()).getPath(), e.getPos(), e == mc.getCameraEntity(),
                            e.shouldRender(s.camera.x, s.camera.y, s.camera.z), e.ignoreCameraFrustum, box, e.getVisibilityBoundingBox());
                    info.drawn = drawnEntities.contains(e.getEntityId());
                    info.inView = e.ignoreCameraFrustum || ((Frustum) frustum).isVisible(box);
                    sec.entities.add(info);
                }
            }
        }

        s.markOpen();
        s.summarize();
        return s;
    }

    // sodium 2.5.1 checks the frustum during the search, so it never says if something off screen would've been
    // culled. running it again with the frustum off answers that - newer versions do it in that order anyway
    private void markOpen() {
        LongSet open = this.reach(false, true, ALL_BANS);
        for (Section sec : this.sections.values()) {
            sec.open = open.contains(ChunkSectionPos.asLong(sec.x, sec.y, sec.z));
        }
    }

    // sections our copy of the search reaches with these rules
    private LongSet reach(boolean frustum, boolean occlusion, int bans) {
        Snapshot g = new Snapshot();
        g.searchCamera = this.searchCamera;
        g.renderDistance = this.renderDistance;
        g.occlusion = this.occlusion && occlusion;
        g.banMask = bans;
        for (Section sec : this.sections.values()) {
            Section c = new Section(sec.x, sec.y, sec.z);
            c.through = sec.through;
            c.inFrustum = !frustum || sec.inFrustum;
            g.sections.put(ChunkSectionPos.asLong(c.x, c.y, c.z), c);
        }
        g.search();
        LongSet out = new LongOpenHashSet();
        for (Section c : g.sections.values()) {
            if (c.visited()) out.add(ChunkSectionPos.asLong(c.x, c.y, c.z));
        }
        return out;
    }

    // off screen sections are FRUSTUM if turning to face them would be enough, otherwise whatever would still cull
    // them
    public Cull cull(Section sec) {
        if (sec.visited()) return Cull.NONE;
        if (!sec.inRange) return Cull.RANGE;
        if (this.culls == null) this.classify();
        return this.culls.get(ChunkSectionPos.asLong(sec.x, sec.y, sec.z));
    }

    private void classify() {
        this.culls = new Long2ObjectOpenHashMap<>();
        Cull[] order = {Cull.HORIZONTAL, Cull.VERTICAL, Cull.BACKTRACKING, Cull.AIR_PATH};
        LongSet[] inView = {this.reach(true, true, VERTICAL_BANS), this.reach(true, true, HORIZONTAL_BANS), this.reach(true, true, 0), this.reach(true, false, ALL_BANS)};
        LongSet[] faced = {this.reach(false, true, VERTICAL_BANS), this.reach(false, true, HORIZONTAL_BANS), this.reach(false, true, 0), this.reach(false, false, ALL_BANS)};
        for (Section sec : this.sections.values()) {
            if (sec.visited() || !sec.inRange) continue;
            long key = ChunkSectionPos.asLong(sec.x, sec.y, sec.z);
            Cull c = Cull.BLOCKED;
            if (sec.open) {
                c = sec.inFrustum ? Cull.ANGLE : Cull.FRUSTUM;
            } else {
                LongSet[] tried = sec.inFrustum ? inView : faced;
                for (int i = 0; i < order.length; i++) {
                    if (tried[i].contains(key)) {
                        c = order[i];
                        break;
                    }
                }
            }
            this.culls.put(key, c);
        }
    }

    public Route route(Section sec) {
        long key = ChunkSectionPos.asLong(sec.x, sec.y, sec.z);
        if (!this.routes.containsKey(key)) this.routes.put(key, Route.of(this, sec));
        return this.routes.get(key);
    }

    // neighbours with an out of date or missing mesh. sodium only remeshes what it can see and keeps the old sight
    // lines until then, so a sealed room can still look open from behind
    public int staleNeighbours(Section sec) {
        int n = 0;
        for (Direction d : Direction.values()) {
            Section adj = this.get(sec.x + d.getOffsetX(), sec.y + d.getOffsetY(), sec.z + d.getOffsetZ());
            if (adj != null && (adj.rebuild || !adj.built)) n++;
        }
        return n;
    }

    public String reason(BlockEntityInfo be, Section sec) {
        if (be.drawn) return "drawn";
        if (!be.renderer) return "no renderer";
        if ((be.queued || be.global) && be.distance >= be.range) return String.format("past %.0f blocks (%.0f)", be.range, be.distance);
        if (!sec.visited()) {
            Cull c = this.cull(sec);
            return (sec.inFrustum || c == Cull.FRUSTUM ? "" : "frustum, ") + c.label.toLowerCase(Locale.ROOT);
        }
        if (!sec.built) return "section not built";
        if (sec.fogged) return "fog";
        return "not queued";
    }

    public String reason(EntityInfo e) {
        if (e.self) return "you";
        if (e.drawn) return "drawn";
        if (!e.inRange) return "entity distance";
        if (!e.inView) return "frustum";
        if (this.entityCulling) return "entity culling";
        return "not drawn";
    }

    private static boolean contains(Collection<BlockEntity> list, BlockEntity be) {
        for (BlockEntity other : list) {
            if (other == be) return true;
        }
        return false;
    }

    // the search again for a width x height window. a resize only changes the projection's horizontal scale, so the
    // recorded one just gets rescaled. view bobbing etc carries over
    public Snapshot simulate(String label, int width, int height) {
        Snapshot s = new Snapshot();
        s.label = label;
        s.time = this.time;
        s.renderDistance = this.renderDistance;
        s.occlusion = this.occlusion;
        s.fogCulling = this.fogCulling;
        s.entityCulling = this.entityCulling;
        s.camera = this.camera;
        s.yaw = this.yaw;
        s.pitch = this.pitch;
        s.model = this.model;
        s.searchCamera = this.searchCamera;
        s.searchWidth = width;
        s.searchHeight = height;

        float k = (float) (((double) this.searchWidth / this.searchHeight) / ((double) width / height));
        s.projection = Matrix4f.scale(k, 1, 1);
        s.projection.multiply(this.projection);
        Frustum frustum = new Frustum(this.model, s.projection);
        frustum.setPosition(this.searchCamera.x, this.searchCamera.y, this.searchCamera.z);

        for (Section sec : this.sections.values()) {
            Section c = sec.copy();
            float ox = c.x << 4, oy = c.y << 4, oz = c.z << 4;
            c.inFrustum = ((FrustumExtended) frustum).fastAabbTest(ox, oy, oz, ox + 16, oy + 16, oz + 16);
            s.sections.put(ChunkSectionPos.asLong(c.x, c.y, c.z), c);
        }
        s.search();

        for (Section sec : s.sections.values()) {
            boolean meshDrawn = sec.visited() && sec.geometry && !sec.fogged;
            for (BlockEntityInfo be : sec.blockEntities) {
                be.queued = meshDrawn && be.inMesh;
                be.drawn = (be.queued || be.global) && be.distance < be.range;
            }
            for (EntityInfo e : sec.entities) {
                e.inView = e.ignoresFrustum || frustum.isVisible(e.box);
                e.drawn = !e.self && e.inRange && e.inView && (!s.entityCulling || s.anyVisible(e.cullBox));
            }
        }

        s.summarize();
        return s;
    }

    // ChunkGraphCuller.initSearch + computeVisible on our copy
    private void search() {
        BlockPos origin = new BlockPos(MathHelper.floor(this.searchCamera.x), MathHelper.floor(this.searchCamera.y), MathHelper.floor(this.searchCamera.z));
        this.centerX = origin.getX() >> 4;
        this.centerY = origin.getY() >> 4;
        this.centerZ = origin.getZ() >> 4;

        List<Section> queue = new ArrayList<>();
        Section root = this.get(this.centerX, this.centerY, this.centerZ);
        if (root != null) {
            this.enqueue(queue, root, null, (byte) 0);
        } else {
            // camera outside the world -> start from every section at the clamped y that's in the frustum, closest
            // first
            int y = MathHelper.clamp(this.centerY, 0, 15);
            List<Section> seeds = new ArrayList<>();
            for (int x = -this.renderDistance; x <= this.renderDistance; x++) {
                for (int z = -this.renderDistance; z <= this.renderDistance; z++) {
                    Section sec = this.get(this.centerX + x, y, this.centerZ + z);
                    if (sec != null && sec.inFrustum) seeds.add(sec);
                }
            }
            seeds.sort(Comparator.comparingDouble(sec -> {
                double dx = (sec.x << 4) + 8 - (origin.getX() + 0.5), dy = (sec.y << 4) + 8 - (origin.getY() + 0.5), dz = (sec.z << 4) + 8 - (origin.getZ() + 0.5);
                return dx * dx + dy * dy + dz * dz;
            }));
            for (Section sec : seeds) this.enqueue(queue, sec, null, (byte) 0);
        }

        for (int i = 0; i < queue.size(); i++) {
            Section node = queue.get(i);
            for (Direction dir : Direction.values()) {
                if ((node.bans & this.banMask & 1 << dir.ordinal()) != 0 || this.occlusion && node.flow != null && !node.canSee(node.flow, dir)) continue;

                Section adj = this.get(node.x + dir.getOffsetX(), node.y + dir.getOffsetY(), node.z + dir.getOffsetZ());
                if (adj == null || Math.abs(adj.x - this.centerX) > this.renderDistance || Math.abs(adj.z - this.centerZ) > this.renderDistance) continue;
                if (adj.visited() || !adj.inFrustum) continue;

                Direction flow = dir.getOpposite();
                this.enqueue(queue, adj, flow, (byte) (node.bans | 1 << flow.ordinal()));
            }
        }
    }

    private void enqueue(List<Section> queue, Section sec, Direction flow, byte bans) {
        sec.order = queue.size();
        sec.flow = flow;
        sec.bans = bans;
        queue.add(sec);
    }

    // SodiumWorldRenderer.isEntityVisible
    private boolean anyVisible(Box box) {
        if (box.maxY < 0.5 || box.minY > 255.5) return true;
        for (int x = MathHelper.floor(box.minX - 0.5) >> 4; x <= MathHelper.floor(box.maxX + 0.5) >> 4; x++) {
            for (int z = MathHelper.floor(box.minZ - 0.5) >> 4; z <= MathHelper.floor(box.maxZ + 0.5) >> 4; z++) {
                for (int y = MathHelper.floor(box.minY - 0.5) >> 4; y <= MathHelper.floor(box.maxY + 0.5) >> 4; y++) {
                    Section sec = this.get(x, y, z);
                    if (sec != null && sec.visited()) return true;
                }
            }
        }
        return false;
    }

    private void summarize() {
        for (Section sec : this.sections.values()) {
            if (sec.visited()) {
                sec.status = Status.VISIBLE;
                this.visible++;
                if (sec.geometry && !sec.fogged) this.drawn++;
            } else if (!sec.inRange) {
                sec.status = Status.OUT_OF_RANGE;
            } else if (sec.inFrustum) {
                sec.status = Status.CULLED;
            } else {
                sec.status = sec.open ? Status.OPEN : Status.OUTSIDE_FOV;
            }

            for (BlockEntityInfo be : sec.blockEntities) {
                if (be.drawn) this.drawnBlockEntities.merge(be.name(), 1, Integer::sum);
            }
            for (EntityInfo e : sec.entities) {
                if (e.drawn) this.drawnEntities.merge(e.type, 1, Integer::sum);
            }
        }
    }

    public double horizontalHalfFov() {
        return Math.atan(1 / ((Matrix4fAccessor) (Object) this.projection).biscuits$a00());
    }

    // replays each neighbour's edge into an unreached t, with computeVisible / bfsEnqueue's checks
    public List<Edge> explain(Section t) {
        if (t.visited() || !t.inRange) return Collections.emptyList();

        List<Edge> out = new ArrayList<>();
        for (Direction d : Direction.values()) {
            Section n = this.get(t.x + d.getOffsetX(), t.y + d.getOffsetY(), t.z + d.getOffsetZ());
            if (n == null) {
                out.add(new Edge(d, Edge.Kind.NO_SECTION, -1, "no section"));
                continue;
            }
            if (!n.visited()) {
                out.add(new Edge(d, Edge.Kind.NOT_VISIBLE, -1, "not reached either"));
                continue;
            }

            Direction travel = d.getOpposite();
            if (n.bans(travel)) {
                out.add(new Edge(d, Edge.Kind.BANNED, n.order, "can't go " + travel.getName() + ", already went " + d.getName()));
            } else if (this.occlusion && n.flow != null && !n.canSee(n.flow, travel)) {
                out.add(new Edge(d, Edge.Kind.NO_PATH, n.order, "no path " + letter(n.flow) + ">" + letter(travel) + " through it"));
            } else if (!t.inFrustum) {
                out.add(new Edge(d, Edge.Kind.OUTSIDE_FOV, n.order, "open, but this section is outside the FOV"));
            } else {
                out.add(new Edge(d, Edge.Kind.OPEN, n.order, "open, changed since the search"));
            }
        }
        return out;
    }

    public static String letter(Direction d) {
        return String.valueOf(Character.toUpperCase(d.getName().charAt(0)));
    }

    public static String letters(byte mask) {
        StringBuilder sb = new StringBuilder();
        for (Direction d : Direction.values()) {
            if ((mask & 1 << d.ordinal()) != 0) sb.append(letter(d));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
