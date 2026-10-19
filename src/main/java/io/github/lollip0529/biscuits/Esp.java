package io.github.lollip0529.biscuits;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lollip0529.biscuits.Snapshot.BlockEntityInfo;
import io.github.lollip0529.biscuits.Snapshot.Edge;
import io.github.lollip0529.biscuits.Snapshot.Section;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// boxes every block entity through walls + outlines stronghold rooms. looking at a silverfish spawner draws the air
// path to it - green as far as the culling search got, red after
public final class Esp {
    private static boolean enabled;

    // spawner colours by mob
    private static final int SILVERFISH = 0xFF69B4, ZOMBIE = 0x22B14C, SPIDER = 0x8B0000, CAVE_SPIDER = 0xFF0000, SKELETON = 0xFFFFFF;
    private static final int GREEN = 0x55FF55, RED = 0xFF5555, GOLD = 0xFFAA00, GRAY = 0xAAAAAA, WHITE = 0xFFFFFF;
    // cost of going through a block, vs 1 for a step through air
    private static final int WALL = 64;
    private static final Map<BlockEntityType<?>, Integer> MAIN = new IdentityHashMap<>();

    static {
        // everything else uses its map colour, close enough
        MAIN.put(BlockEntityType.CHEST, 0xA8742A);
        MAIN.put(BlockEntityType.TRAPPED_CHEST, 0xB0502A);
        MAIN.put(BlockEntityType.ENDER_CHEST, 0x1E5E56);
        MAIN.put(BlockEntityType.END_PORTAL, 0x2AB09A);
        MAIN.put(BlockEntityType.END_GATEWAY, 0x8A50D0);
        MAIN.put(BlockEntityType.SKULL, 0xD6D2B8);
    }

    // throughWalls = rooms the path goes through, only those draw through walls
    private static List<Strongholds.Piece> rooms = new ArrayList<>(), throughWalls = new ArrayList<>();
    private static BlockPos target, traced;
    private static Snapshot snap;
    private static Route route;
    private static long captured;
    // path start - the camera, or the starter's eye in spectator
    private static Vec3d from;
    private static boolean fromStarter;
    private static BlockPos tracedFrom;
    private static long tracedAt;
    private static List<BlockPos> blocks, walls;
    private static boolean sealed;
    // eye, then the centre of each block. everything before green is drawn green
    private static List<Vec3d> path;
    private static int green;
    private static Section cutFrom, cutTo;
    private static String reason;
    // walk() buffers, reused between traces
    private static int[] dist = new int[0];
    private static byte[] kind = new byte[0];

    private static final class Text {
        public final String text;
        public final int color;

        Text(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private Esp() {
    }

    public static void toggle(MinecraftClient mc) {
        enabled = !enabled;
        target = null;
        traced = null;
        if (enabled) Strongholds.refresh(mc, true);
        mc.inGameHud.setOverlayMessage(new LiteralText("ESP " + (enabled ? "on" : "off")), false);
    }

    private static int color(BlockEntity be) {
        if (be instanceof MobSpawnerBlockEntity) {
            EntityType<?> mob = mob(be);
            if (mob == EntityType.SILVERFISH) return SILVERFISH;
            if (mob == EntityType.ZOMBIE) return ZOMBIE;
            if (mob == EntityType.SPIDER) return SPIDER;
            if (mob == EntityType.CAVE_SPIDER) return CAVE_SPIDER;
            if (mob == EntityType.SKELETON) return SKELETON;
            SpawnEggItem egg = SpawnEggItem.forEntity(mob);
            return egg == null ? 0x8090A0 : egg.getColor(0) & 0xFFFFFF;
        }
        Integer main = MAIN.get(be.getType());
        if (main != null) return main;
        int map = be.getCachedState().getTopMaterialColor(be.getWorld(), be.getPos()).color;
        return map == 0 ? WHITE : map;
    }

    private static EntityType<?> mob(BlockEntity be) {
        if (!(be instanceof MobSpawnerBlockEntity)) return null;
        Entity shown = ((MobSpawnerBlockEntity) be).getLogic().getRenderedEntity();
        return shown == null ? null : shown.getType();
    }

    // runs after the world, so it's outside the entities section the pie chart times
    public static void render(MatrixStack matrices, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        rooms = new ArrayList<>();
        throughWalls = new ArrayList<>();
        target = enabled && mc.world != null ? pick(mc.world, camera.getPos(), Vec3d.fromPolar(camera.getPitch(), camera.getYaw())) : null;
        if (target == null) forget();
        if (!enabled || mc.world == null) return;

        Strongholds.refresh(mc, false);
        Vec3d cam = camera.getPos();
        if (target != null) update(mc, cam);

        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(matrices.peek().getModel());
        RenderSystem.disableTexture();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();

        // block entities, faces then outlines
        List<BlockEntity> all = new ArrayList<>(mc.world.blockEntities);
        b.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        for (BlockEntity be : all) {
            faces(b, cam, be.getPos(), color(be), 0.18f);
        }
        t.draw();

        RenderSystem.lineWidth(2);
        b.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
        for (BlockEntity be : all) {
            BlockPos p = be.getPos();
            box(b, cam, p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, color(be), 1);
        }
        t.draw();

        // rooms
        double reach = (mc.options.viewDistance + 1) * 16;
        for (Strongholds.Stronghold s : Strongholds.all()) {
            BlockBox bb = s.bounds;
            double dx = Math.max(0, Math.max(bb.minX - cam.x, cam.x - bb.maxX - 1)), dz = Math.max(0, Math.max(bb.minZ - cam.z, cam.z - bb.maxZ - 1));
            if (dx > reach || dz > reach) continue;
            for (Strongholds.Piece p : s.pieces) {
                rooms.add(p);
                if (blocks != null && crosses(p)) throughWalls.add(p);
            }
        }
        RenderSystem.enableDepthTest();
        b.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
        // depth tested, the ones on the path go on top after
        for (Strongholds.Piece p : rooms) if (!throughWalls.contains(p)) room(b, cam, p);
        t.draw();
        RenderSystem.disableDepthTest();
        b.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
        for (Strongholds.Piece p : throughWalls) room(b, cam, p);
        t.draw();

        if (path != null) drawPath(b, cam);

        RenderSystem.enableTexture();
        // StrongholdTrainer style label for the room you're in
        Strongholds.Piece room = Strongholds.room(new BlockPos(cam));
        if (room != null) {
            Vec3d c = new Vec3d((room.box.minX + room.box.maxX + 1) / 2.0, (room.box.minY + room.box.maxY + 1) / 2.0, (room.box.minZ + room.box.maxZ + 1) / 2.0);
            label("Type: " + room.name(), c, cam, WHITE, 0);
            label("Depth: " + room.depth, c, cam, WHITE, 1);
            label("Direction: " + (room.facing == null ? "none" : room.facing.getName()), c, cam, WHITE, 2);
        }

        RenderSystem.lineWidth(1);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.popMatrix();
    }

    // slightly outside the walls or it z-fights
    private static void room(BufferBuilder b, Vec3d cam, Strongholds.Piece p) {
        box(b, cam, p.box.minX - 0.01, p.box.minY - 0.01, p.box.minZ - 0.01, p.box.maxX + 1.01, p.box.maxY + 1.01, p.box.maxZ + 1.01, WHITE, 1);
    }

    private static void forget() {
        traced = tracedFrom = null;
        route = null;
        from = null;
        blocks = walls = null;
        path = null;
        cutFrom = cutTo = null;
        reason = null;
    }

    private static boolean crosses(Strongholds.Piece p) {
        for (BlockPos pos : blocks) if (p.box.contains(pos)) return true;
        return false;
    }

    private static void drawPath(BufferBuilder b, Vec3d cam) {
        Tessellator t = Tessellator.getInstance();
        RenderSystem.lineWidth(4);
        b.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
        box(b, cam, target.getX() - 0.05, target.getY() - 0.05, target.getZ() - 0.05, target.getX() + 1.05, target.getY() + 1.05, target.getZ() + 1.05, SILVERFISH, 1);
        for (BlockPos w : walls) box(b, cam, w.getX(), w.getY(), w.getZ(), w.getX() + 1, w.getY() + 1, w.getZ() + 1, GOLD, 1);
        t.draw();

        RenderSystem.lineWidth(3);
        b.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
        for (int i = 0; i + 1 < path.size(); i++) {
            int c = i + 1 < green ? GREEN : RED;
            vertex(b, cam, path.get(i), c, 1);
            vertex(b, cam, path.get(i + 1), c, 1);
        }
        t.draw();
        RenderSystem.lineWidth(1);

        RenderSystem.enableTexture();
        if (route != null) {
            // pastry style labels on the sections the green part goes through
            Set<Section> seen = new LinkedHashSet<>();
            for (int i = 0; i < blocks.size() && i + 1 < green; i++) {
                BlockPos p = blocks.get(i);
                Section sec = snap.get(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
                if (sec != null && route.reached(sec)) seen.add(sec);
            }
            for (Section sec : seen) {
                label("#" + route.order(sec) + " Blocked: " + Snapshot.letters(route.bans(sec)), new Vec3d((sec.x << 4) + 8, (sec.y << 4) + 8, (sec.z << 4) + 8), cam, WHITE, 0);
            }
            if (reason != null && green < path.size()) label("cut: " + reason, path.get(green), cam, RED, 1);
        }
        RenderSystem.disableTexture();
        RenderSystem.disableDepthTest();
    }

    // scales with distance so it stays about the same size on screen
    private static void label(String text, Vec3d at, Vec3d cam, int color, int line) {
        float size = (float) Math.max(0.02, at.distanceTo(cam) * 0.003);
        DebugRenderer.drawString(text, at.x, at.y - line * 11 * size, at.z, 0xFF000000 | color, size, true, 0, true);
    }

    // silverfish spawner closest to the crosshair. the block itself, or within 2 degrees once it's too far away to
    // aim at
    private static BlockPos pick(World world, Vec3d eye, Vec3d look) {
        BlockPos best = null;
        double bestAngle = Double.MAX_VALUE;
        for (BlockEntity be : world.blockEntities) {
            if (mob(be) != EntityType.SILVERFISH) continue;
            Vec3d to = Vec3d.ofCenter(be.getPos()).subtract(eye);
            double d = to.length();
            double angle = Math.acos(MathHelper.clamp(look.dotProduct(to) / d, -1, 1));
            if (angle < Math.max(Math.atan(0.9 / d), Math.toRadians(2)) && angle < bestAngle) {
                best = be.getPos();
                bestAngle = angle;
            }
        }
        return best;
    }

    private static void update(MinecraftClient mc, Vec3d cam) {
        long now = Util.getMeasuringTimeMs();
        // same spawner -> recapture at most every 250ms
        if (target.equals(traced) && now - captured < 250) return;
        Snapshot s = Snapshot.capture(mc);
        if (s == null) return;
        boolean moved = !target.equals(traced);
        snap = s;
        captured = now;
        traced = target;

        Section sec = s.get(target.getX() >> 4, target.getY() >> 4, target.getZ() >> 4);
        from = cam;
        fromStarter = false;
        route = null;
        if (sec != null) {
            // spectator -> start from the stronghold's starter instead of the camera
            Strongholds.Stronghold sh = mc.player != null && mc.player.isSpectator() ? Strongholds.containing(target) : null;
            Section root = null;
            if (sh != null && sh.starter != null) {
                Vec3d eye = sh.eye();
                root = s.get(MathHelper.floor(eye.x) >> 4, MathHelper.floor(eye.y) >> 4, MathHelper.floor(eye.z) >> 4);
                if (root != null) {
                    from = eye;
                    fromStarter = true;
                }
            }
            route = root != null ? Route.from(s, sec, root) : s.route(sec);
        }

        BlockPos start = new BlockPos(from);
        if (moved || !start.equals(tracedFrom) || now - tracedAt > 5000) {
            tracedFrom = start;
            tracedAt = now;
            trace(mc.world, start);
        }
        colour();
    }

    // same area as the explainer: the box around both sections + 2 sections each way
    private static void trace(World world, BlockPos start) {
        int x0 = Math.min(start.getX(), target.getX()) >> 4, y0 = Math.min(start.getY(), target.getY()) >> 4, z0 = Math.min(start.getZ(), target.getZ()) >> 4;
        int x1 = Math.max(start.getX(), target.getX()) >> 4, y1 = Math.max(start.getY(), target.getY()) >> 4, z1 = Math.max(start.getZ(), target.getZ()) >> 4;
        int[] box = {(x0 - 2) << 4, Math.max(0, (y0 - 2) << 4), (z0 - 2) << 4, ((x1 + 3) << 4) - 1, Math.min(255, ((y1 + 3) << 4) - 1), ((z1 + 3) << 4) - 1};
        walls = Collections.emptyList();
        blocks = walk(world, box, start, target, false);
        sealed = blocks == null;
        if (sealed) {
            // no air path at all - take the one that breaks the fewest blocks, like the explainer does for hidden
            // rooms
            blocks = walk(world, box, start, target, true);
            if (blocks != null) {
                walls = new ArrayList<>();
                for (BlockPos p : blocks.subList(1, blocks.size() - 1)) if (opaque(world, p)) walls.add(p);
            }
        }
        if (blocks == null) {
            path = null;
            return;
        }
        path = new ArrayList<>();
        path.add(from);
        for (BlockPos p : blocks) path.add(Vec3d.ofCenter(p));
    }

    // green up to the first block in a section the search never got to
    private static void colour() {
        cutFrom = cutTo = null;
        reason = null;
        if (path == null) return;
        green = path.size();
        if (route == null || route.group == Route.Group.REACHED) return;
        Section prev = null;
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos p = blocks.get(i);
            Section sec = snap.get(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
            if (sec != null && route.reached(sec)) {
                prev = sec;
                continue;
            }
            green = i + 1;
            cutFrom = prev;
            cutTo = sec;
            reason = prev == null || sec == null ? "outside the loaded sections" : route.why(prev, sec);
            return;
        }
    }

    // cost from the spawner to every block in the box, cheapest first (dial's algorithm). air only, or solid blocks
    // cost WALL. then walk it back from the start, going straight whenever that's just as short
    private static List<BlockPos> walk(World world, int[] box, BlockPos start, BlockPos goal, boolean through) {
        if (!inside(box, start) || !inside(box, goal)) return null;
        int ny = box[4] - box[1] + 1, nz = box[5] - box[2] + 1, volume = (box[3] - box[0] + 1) * ny * nz;
        if (dist.length < volume) {
            dist = new int[volume];
            kind = new byte[volume];
        }
        Arrays.fill(dist, 0, volume, -1);
        Arrays.fill(kind, 0, volume, (byte) 0);
        int s = index(box, ny, nz, start.getX(), start.getY(), start.getZ()), g = index(box, ny, nz, goal.getX(), goal.getY(), goal.getZ());
        kind[g] = 1;

        IntArrayFIFOQueue[] buckets = new IntArrayFIFOQueue[WALL + 1];
        for (int i = 0; i < buckets.length; i++) buckets[i] = new IntArrayFIFOQueue();
        BlockPos.Mutable m = new BlockPos.Mutable();
        dist[g] = 0;
        buckets[0].enqueue(g);
        int queued = 1, cost = 0;
        while (queued > 0) {
            IntArrayFIFOQueue q = buckets[cost % buckets.length];
            if (q.isEmpty()) {
                cost++;
                continue;
            }
            int i = q.dequeueInt();
            queued--;
            if (dist[i] < cost) continue;
            if (i == s) break;
            int enter = solid(world, box, ny, nz, i, m) ? WALL : 1;
            int x = box[0] + i / (ny * nz), y = box[1] + i / nz % ny, z = box[2] + i % nz;
            for (int dir = 0; dir < 6; dir++) {
                int n = neighbour(box, ny, nz, x, y, z, dir);
                if (n < 0 || !through && solid(world, box, ny, nz, n, m)) continue;
                int nd = cost + enter;
                if (dist[n] == -1 || nd < dist[n]) {
                    dist[n] = nd;
                    buckets[nd % buckets.length].enqueue(n);
                    queued++;
                }
            }
        }
        if (dist[s] < 0) return null;

        List<BlockPos> out = new ArrayList<>();
        int cur = s, dir = -1;
        out.add(start);
        while (cur != g) {
            int x = box[0] + cur / (ny * nz), y = box[1] + cur / nz % ny, z = box[2] + cur % nz;
            int next = -1, nextDir = -1;
            for (int k = -1; k < 6 && next < 0; k++) {
                int dd = k < 0 ? dir : k;
                if (dd < 0 || k >= 0 && dd == dir) continue;
                int n = neighbour(box, ny, nz, x, y, z, dd);
                if (n < 0 || dist[n] < 0) continue;
                if (dist[n] + (solid(world, box, ny, nz, n, m) ? WALL : 1) == dist[cur]) {
                    next = n;
                    nextDir = dd;
                }
            }
            if (next < 0) return null;
            cur = next;
            dir = nextDir;
            out.add(new BlockPos(box[0] + cur / (ny * nz), box[1] + cur / nz % ny, box[2] + cur % nz));
        }
        return out;
    }

    // sodium's flood fill goes through anything that isn't an opaque full cube
    private static boolean solid(World world, int[] box, int ny, int nz, int i, BlockPos.Mutable m) {
        if (kind[i] == 0) {
            m.set(box[0] + i / (ny * nz), box[1] + i / nz % ny, box[2] + i % nz);
            kind[i] = (byte) (world.getBlockState(m).isOpaqueFullCube(world, m) ? 2 : 1);
        }
        return kind[i] == 2;
    }

    private static boolean opaque(World world, BlockPos p) {
        return world.getBlockState(p).isOpaqueFullCube(world, p);
    }

    private static int neighbour(int[] box, int ny, int nz, int x, int y, int z, int dir) {
        int ax = x + (dir == 4 ? -1 : dir == 5 ? 1 : 0), ay = y + (dir == 0 ? -1 : dir == 1 ? 1 : 0), az = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
        if (ax < box[0] || ay < box[1] || az < box[2] || ax > box[3] || ay > box[4] || az > box[5]) return -1;
        return index(box, ny, nz, ax, ay, az);
    }

    private static int index(int[] box, int ny, int nz, int x, int y, int z) {
        return ((x - box[0]) * ny + (y - box[1])) * nz + (z - box[2]);
    }

    private static boolean inside(int[] box, BlockPos p) {
        return p.getX() >= box[0] && p.getY() >= box[1] && p.getZ() >= box[2] && p.getX() <= box[3] && p.getY() <= box[4] && p.getZ() <= box[5];
    }

    // hud box next to the crosshair
    private static List<Text> lines() {
        List<Text> out = new ArrayList<>();
        if (target == null) return out;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d eye = from == null ? mc.gameRenderer.getCamera().getPos() : from;
        double away = Vec3d.ofCenter(target).distanceTo(eye);
        out.add(new Text(String.format("Silverfish spawner  %d %d %d  %.1f blocks", target.getX(), target.getY(), target.getZ(), away), SILVERFISH));
        if (fromStarter) {
            out.add(new Text(String.format("From the starter %.1f %.1f %.1f", eye.x, eye.y - 1.62, eye.z), GRAY));
        } else if (mc.player != null && mc.player.isSpectator()) {
            out.add(new Text("No starter known here, so from the camera", GOLD));
        }
        if (route == null) {
            out.add(new Text("Not in Sodium's graph", GRAY));
            return out;
        }

        Section sec = route.target;
        if (route.group == Route.Group.REACHED) {
            out.add(new Text("Reached #" + route.order(sec) + (route.replayed ? " with a full view" : sec.flow == null ? ", the camera's section" : ", in through " + sec.flow.getName()), GREEN));
            out.add(new Text("Blocked: " + Snapshot.letters(route.bans(sec)), GRAY));
        } else {
            out.add(new Text("Culled: " + route.group.label, GOLD));
            if (cutTo != null) out.add(new Text("Cut at " + cutTo.x + " " + cutTo.y + " " + cutTo.z + " from #" + route.order(cutFrom) + ": " + reason, RED));
            if (cutFrom != null) out.add(new Text("Blocked at #" + route.order(cutFrom) + ": " + Snapshot.letters(route.bans(cutFrom)), GRAY));
            if (route.fixes() != null) out.add(new Text("Fixes: " + route.fixes(), GRAY));
            if (!route.replayed) {
                for (Edge e : snap.explain(sec)) {
                    if (e.from >= 0) out.add(new Text(Snapshot.letter(e.dir) + "  #" + e.from + " " + e.text, GRAY));
                }
            }
        }
        if (!route.replayed && blocks != null) {
            List<Section> along = new ArrayList<>();
            for (BlockPos p : blocks) {
                Section s = snap.get(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
                if (s != null && !along.contains(s)) along.add(s);
            }
            int stale = route.stale(along);
            if (stale > 0) {
                out.add(new Text(stale + " section" + (stale == 1 ? "" : "s") + " on the way " + (stale == 1 ? "has" : "have") + " an out-of-date or missing mesh; Sodium uses the old sight lines", GOLD));
            }
        }
        String who = fromStarter ? "the starter" : "your eye";
        if (sealed && walls != null) {
            StringBuilder sb = new StringBuilder("Sealed: break " + walls.size() + " block" + (walls.size() == 1 ? "" : "s"));
            for (int i = 0; i < Math.min(3, walls.size()); i++) sb.append(i == 0 ? ": " : ", ").append(walls.get(i).getX()).append(' ').append(walls.get(i).getY()).append(' ').append(walls.get(i).getZ());
            if (walls.size() > 3) sb.append(", ...");
            out.add(new Text(sb.toString(), GOLD));
        } else if (sealed) {
            out.add(new Text("Sealed: no air path from " + who, GOLD));
        } else {
            out.add(new Text("Air path from " + who + ": " + (blocks.size() - 1) + " blocks", GRAY));
        }
        if (fromStarter) {
            // block entities don't render past 64, so from the starter it'd never be drawn
            if (away >= 64) out.add(new Text("Past 64 blocks from the starter: never drawn there", GOLD));
        } else {
            for (BlockEntityInfo be : sec.blockEntities) {
                if (be.pos.equals(target)) out.add(be.drawn ? new Text("Drawn last frame", GREEN) : new Text("Not drawn: " + snap.reason(be, sec), GOLD));
            }
        }
        return out;
    }

    public static void hud(MatrixStack matrices) {
        if (!enabled || target == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        List<Text> lines = lines();
        int w = 0;
        for (Text l : lines) w = Math.max(w, mc.textRenderer.getWidth(l.text));
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int x = Math.max(2, Math.min(sw / 2 + 12, sw - w - 4)), y = Math.min(sh / 2 + 10, sh - lines.size() * 10 - 4);
        DrawableHelper.fill(matrices, x - 3, y - 3, x + w + 3, y + lines.size() * 10 + 1, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            mc.textRenderer.drawWithShadow(matrices, lines.get(i).text, x, y + i * 10, lines.get(i).color);
        }
    }

    private static void box(BufferBuilder b, Vec3d cam, double x0, double y0, double z0, double x1, double y1, double z1, int c, float a) {
        double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                vertex(b, cam, new Vec3d(x0, ys[i], zs[j]), c, a);
                vertex(b, cam, new Vec3d(x1, ys[i], zs[j]), c, a);
                vertex(b, cam, new Vec3d(xs[i], y0, zs[j]), c, a);
                vertex(b, cam, new Vec3d(xs[i], y1, zs[j]), c, a);
                vertex(b, cam, new Vec3d(xs[i], ys[j], z0), c, a);
                vertex(b, cam, new Vec3d(xs[i], ys[j], z1), c, a);
            }
        }
    }

    private static void faces(BufferBuilder b, Vec3d cam, BlockPos p, int c, float a) {
        float x0 = (float) (p.getX() - cam.x), y0 = (float) (p.getY() - cam.y), z0 = (float) (p.getZ() - cam.z);
        float x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        float[][] quads = {
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}, {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}};
        float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, bl = (c & 255) / 255f;
        for (float[] q : quads) {
            for (int i = 0; i < 12; i += 3) b.vertex(q[i], q[i + 1], q[i + 2]).color(r, g, bl, a).next();
        }
    }

    private static void vertex(BufferBuilder b, Vec3d cam, Vec3d p, int c, float a) {
        b.vertex(p.x - cam.x, p.y - cam.y, p.z - cam.z).color((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, a).next();
    }
}
